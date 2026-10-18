#include "MealVision.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "ggml-backend.h"
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

static std::atomic<bool> cancelled(false);
static std::once_flag initialized;
static std::mutex inference_mutex;
using Clock = std::chrono::steady_clock;
struct Control { Clock::time_point deadline; };
static bool abort_inference(void * data) {
    return cancelled.load() || Clock::now() > static_cast<Control *>(data)->deadline;
}
static bool load_progress(float, void * data) { return !abort_inference(data); }
static void quiet_log(ggml_log_level, const char *, void *) {}
extern "C" void mealvision_cancel(void) { cancelled = true; }
extern "C" void mealvision_free(char * value) { free(value); }

extern "C" char * mealvision_recognize(const char * model_path, const char * projector,
                                       const char * image, int use_gpu, char ** error) {
    std::lock_guard<std::mutex> lock(inference_mutex);
    if (error) *error = nullptr;
    cancelled = false;
    Control control { Clock::now() + std::chrono::seconds(150) };
    try {
        std::call_once(initialized, [] {
            llama_log_set(quiet_log, nullptr);
            mtmd_helper_log_set(quiet_log, nullptr);
            ggml_backend_load_all();
            llama_backend_init();
        });
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = use_gpu ? 99 : 0;
        mp.progress_callback = load_progress;
        mp.progress_callback_user_data = &control;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(llama_model_load_from_file(model_path, mp), llama_model_free);
        if (!model) throw std::runtime_error("无法载入本地语言模型，或任务已取消");
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048; cp.n_batch = 512; cp.n_ubatch = 512;
        cp.n_threads = 4; cp.n_threads_batch = 4;
        cp.abort_callback = abort_inference; cp.abort_callback_data = &control;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("本地模型内存不足，请关闭其他应用后重试");
        auto vp = mtmd_context_params_default();
        vp.use_gpu = use_gpu; vp.n_threads = 4; vp.image_max_tokens = 512;
        vp.progress_callback = load_progress; vp.progress_callback_user_data = &control;
        std::unique_ptr<mtmd_context, decltype(&mtmd_free)> vision(mtmd_init_from_file(projector, model.get(), vp), mtmd_free);
        if (!vision) throw std::runtime_error("无法载入视觉模型");
        auto loaded = mtmd_helper_bitmap_init_from_file(vision.get(), image, false, mtmd_helper_init_opt_default());
        std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmap(loaded.bitmap, mtmd_bitmap_free);
        if (!bitmap) throw std::runtime_error("照片解码失败");
        // Qwen3.5's documented ChatML framing with an empty thinking block disables reasoning.
        std::string prompt = "<|im_start|>user\n";
        prompt += mtmd_default_marker();
        prompt += "\n识别照片中看得见的主要食物，最多5项，估计可食部分克数。只输出JSON，格式：{\"food\":true,\"name\":\"菜名\",\"items\":[{\"name\":\"食物中文名称\",\"grams\":100}],\"oil\":5}。oil是额外烹调油克数。没有食物则输出{\"food\":false}。不要列出照片以外的食物。<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        mtmd_input_text input { prompt.c_str(), prompt.size(), true, true };
        const mtmd_bitmap * images[] = { bitmap.get() };
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
        if (mtmd_tokenize(vision.get(), chunks.get(), &input, images, 1) != 0) throw std::runtime_error("视觉输入处理失败");
        llama_pos past = 0;
        if (abort_inference(&control) || mtmd_helper_eval_chunks(vision.get(), context.get(), chunks.get(), 0, 0, 512, true, &past))
            throw std::runtime_error("视觉计算被中断，可重试或手动记录");
        const auto * vocab = llama_model_get_vocab(model.get());
        const char * grammar = R"GBNF(root ::= "{" ws "\"food\"" ws ":" ws ("false" ws "}" | "true" ws "," ws "\"name\"" ws ":" ws string ws "," ws "\"items\"" ws ":" ws "[" ws item (ws "," ws item)? (ws "," ws item)? (ws "," ws item)? (ws "," ws item)? ws "]" ws "," ws "\"oil\"" ws ":" ws number ws "}")
item ::= "{" ws "\"name\"" ws ":" ws string ws "," ws "\"grams\"" ws ":" ws number ws "}"
string ::= "\"" ([^"\\\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\""
number ::= [0-9]{1,4} ("." [0-9]{1,2})?
ws ::= [ \t\n\r]*
)GBNF";
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        auto * grammar_sampler = llama_sampler_init_grammar(vocab, grammar, "root");
        if (!grammar_sampler) throw std::runtime_error("无法初始化输出格式约束");
        llama_sampler_chain_add(sampler.get(), grammar_sampler);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        std::string output;
        bool ended = false;
        for (int i = 0; i < 350 && past + i < 2047; ++i) {
            if (abort_inference(&control)) throw std::runtime_error("本地识别已取消或超时");
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            std::vector<char> piece(256);
            int n = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, false);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), (int)piece.size(), 0, false); }
            if (n > 0) output.append(piece.data(), n);
            if (llama_decode(context.get(), llama_batch_get_one(&token, 1))) throw std::runtime_error("本地生成被中断");
        }
        if (!ended || output.empty()) throw std::runtime_error("模型未能完成食物结果，可重试或手动补记");
        return strdup(output.c_str());
    } catch (const std::exception & e) {
        if (error) *error = strdup(e.what());
        return nullptr;
    }
}
