// MealTrace private stdio protocol. No network listener or retained conversation.
#include <iostream>
#include "nlohmann/json.hpp"

static int mealtrace_worker(mtmd_cli_context & ctx, int n_predict, const common_params_sampling & sampling) {
    using nlohmann::json;
    common_log_flush(common_log_main());
    std::puts("{\"ready\":true,\"protocol\":1}");
    std::fflush(stdout);
    std::string line;
    while (std::getline(std::cin, line)) {
        json response;
        try {
            if (line.size() > 65536) throw std::runtime_error("request too large");
            auto request = json::parse(line);
            response["id"] = request.at("id");
            const auto image = request.at("image").get<std::string>();
            const auto prompt = request.at("prompt").get<std::string>();
            ctx.n_past = 0;
            ctx.chat_history.clear();
            ctx.bitmaps.entries.clear();
            ctx.videos.clear();
            ctx.mbatch.reset();
            llama_memory_clear(llama_get_memory(ctx.lctx), true);
            // common_sampler_reset() only resets the chain, not its JSON grammar.
            common_sampler_free(ctx.smpl);
            ctx.smpl = nullptr;
            auto parameters = sampling;
            ctx.smpl = common_sampler_init(ctx.model, parameters);
            if (!ctx.smpl) throw std::runtime_error("sampler initialization failed");
            llama_perf_context_reset(ctx.lctx);
            g_is_generating = true;
            if (!ctx.load_media(image)) throw std::runtime_error("image load failed");
            common_chat_msg msg;
            msg.role = "user";
            msg.content = mtmd_default_marker() + prompt;
            if (eval_message(ctx, msg)) throw std::runtime_error("image/prompt evaluation failed");
            std::string text;
            if (generate_response(ctx, n_predict, &text)) throw std::runtime_error("generation failed");
            response["result"] = json::parse(text);
        } catch (const std::exception & error) {
            response["error"] = error.what();
        }
        common_log_flush(common_log_main());
        std::puts(response.dump().c_str());
        std::fflush(stdout);
    }
    return 0;
}
