#!/usr/bin/env python3
"""Apply the MealTrace stdio worker extension to the pinned no-thinking runner."""
from pathlib import Path
import shutil
import sys


def apply(source):
    cli = source / "tools/mtmd/mtmd-cli.cpp"
    text = cli.read_text()
    if "mealtrace_worker(ctx" in text:
        cli.write_text(text.replace("mealtrace_worker(ctx, n_predict)",
                                    "mealtrace_worker(ctx, n_predict, params.sampling)"))
        shutil.copyfile(Path(__file__).with_name("worker.inc"), cli.with_name("mealtrace-worker.inc"))
        return
    edits = [
        ("static int generate_response(mtmd_cli_context & ctx, int n_predict) {",
         "static int generate_response(mtmd_cli_context & ctx, int n_predict, std::string * output = nullptr) {"),
        ('        LOG("%s", common_token_to_piece(ctx.lctx, token_id).c_str());\n        fflush(stdout);',
         '        if (output) *output += common_token_to_piece(ctx.lctx, token_id);\n'
         '        else { LOG("%s", common_token_to_piece(ctx.lctx, token_id).c_str()); fflush(stdout); }'),
        ("int main(int argc, char ** argv) {", '#include "mealtrace-worker.inc"\n\nint main(int argc, char ** argv) {'),
        ("    console::init(params.simple_io, params.use_color);",
         '    if (std::getenv("MEALTRACE_WORKER")) return mealtrace_worker(ctx, n_predict, params.sampling);\n\n'
         '    console::init(params.simple_io, params.use_color);'),
    ]
    for old, new in edits:
        if text.count(old) != 1:
            raise SystemExit("Pinned worker patch anchor changed: " + old)
        text = text.replace(old, new)
    cli.write_text(text)
    shutil.copyfile(Path(__file__).with_name("worker.inc"), cli.with_name("mealtrace-worker.inc"))


if __name__ == "__main__":
    apply(Path(sys.argv[1]))
