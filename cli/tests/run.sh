#!/usr/bin/env bash
# cli/tests/run.sh — CLI 黑盒 golden 回归（AGENTS.md §2.5/§4）
#
# 用法: ./cli/tests/run.sh [--fast]
#   --fast  跳过依赖本机 redis/PG 的 LAB 用例（仅契约用例）
#
# 断言模式：给定 argv → 断言 退出码 + 信封形状（ok/task/tool/error 等字段）
# 纪律：失败即失败；实验室环境不可达 → 记 BLOCKED 并继续（不伪造通过）。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$ROOT/cli/tests/.work"
FAST=0
[ "${1:-}" = "--fast" ] && FAST=1

PASS=0; FAIL=0; BLOCKED=0
declare -a FAILED_CASES=()

say()  { printf '%s\n' "$*"; }
pass() { PASS=$((PASS+1)); say "  PASS $1"; }
fail() { FAIL=$((FAIL+1)); FAILED_CASES+=("$1"); say "  FAIL $1 — $2"; }
blocked() { BLOCKED=$((BLOCKED+1)); say "  BLOCKED $1 — $2（环境不可达）"; }

# ---- 信封断言: expect_env <case> <exit_expect> <jar输出文件> <python断言式(对 d)> ----
EXPECT_EXIT=""
expect_env() {
    local got_exit="${EXPECT_EXIT:-$?}"
    EXPECT_EXIT=""
    local case_name="$1" want_exit="$2" outfile="$3" pyexpr="$4"
    if ! python3 -c "
import json,sys
raw=open('$outfile',encoding='utf-8').read().strip()
assert raw, '信封为空'
d=json.loads(raw.splitlines()[-1])
assert ($pyexpr), '断言失败: '+raw[:200]
" 2>/tmp/mdut-golden-pyerr; then
        fail "$case_name" "信封断言失败: $(tail -1 /tmp/mdut-golden-pyerr)"
        return
    fi
    if [ "$got_exit" != "$want_exit" ]; then
        fail "$case_name" "退出码 want=$want_exit got=$got_exit"
        return
    fi
    pass "$case_name"
}

# ---- 准备工作区（jar + Driver/Plugins 镜像） ----
mkdir -p "$WORK"
if [ ! -f "$ROOT/MDAT-DEV/target/mdut-jar-with-dependencies.jar" ]; then
    say "[setup] 构建 jar..."
    (cd "$ROOT/MDAT-DEV" && mvn -q -s "$ROOT/.m2settings.xml" -Dmaven.repo.local="$ROOT/.m2repo" package) || { say "[setup] 构建失败"; exit 1; }
fi
cp "$ROOT/MDAT-DEV/target/mdut-jar-with-dependencies.jar" "$WORK/mdut.jar"
rm -rf "$WORK/Driver" "$WORK/Plugins"
cp -r "$ROOT/MDAT-DEV/src/main/Driver" "$WORK/Driver"
cp -r "$ROOT/MDAT-DEV/src/main/Plugins" "$WORK/Plugins"

export MDUT_JAR="$WORK/mdut.jar"
export MDUT_TASKS_ROOT="$WORK/tasks"
MDUT="$ROOT/cli/mdut"

say "== 契约用例 =="
# 1 version：单行信封
"$MDUT" version >"$WORK/o1" 2>/dev/null; e=$?
if [ $e -eq 0 ] && python3 -c "
import json;raw=open('$WORK/o1').read().strip()
d=json.loads(raw.splitlines()[-1])
assert d['ok'] is True and d['tool']=='version' and d['data']['version'].startswith('v2.1.1-cli')
assert len(raw.splitlines())==1"; then pass "version-信封单行"; else fail "version-信封单行" "exit=$e $(cat "$WORK/o1")"; fi

# 2 无参 → exit 2 + 用法
"$MDUT" >"$WORK/o2" 2>/dev/null; e=$?
if [ $e -eq 2 ] && grep -q "退出码" "$WORK/o2"; then pass "无参-用法与免责"; else fail "无参-用法与免责" "exit=$e"; fi

# 3 未知命令 → exit 2 + hint
"$MDUT" frobnicate >"$WORK/o3" 2>/dev/null
expect_env "未知命令-exit2" 2 "$WORK/o3" "d['ok'] is False and 'hint' in d"

# 4 help exec → 0 且含参数级文档
"$MDUT" help exec >"$WORK/o4" 2>/dev/null; e=$?
if [ $e -eq 0 ] && grep -q -- "--method" "$WORK/o4"; then pass "help-exec-参数级"; else fail "help-exec-参数级" "exit=$e"; fi

# 5 --help → 0
"$MDUT" --help >/dev/null 2>&1; [ $? -eq 0 ] && pass "--help-exit0" || fail "--help-exit0" ""

# 6 非法 task 名（wrapper 守卫）
"$MDUT" --task '../evil' list >/dev/null 2>&1; [ $? -eq 2 ] && pass "task名守卫-exit2" || fail "task名守卫-exit2" ""

# 7 连接测试失败（拒绝端口）→ exit 3 + hint
"$MDUT" --task g add mysql --host 127.0.0.1 --port 1 --timeout 1 >"$WORK/o7" 2>/dev/null; e=$?
if [ $e -eq 3 ] && python3 -c "
import json;d=json.loads(open('$WORK/o7').read())
assert d['ok'] is False and d['tool']=='add' and d.get('hint')"; then pass "连接失败-exit3+hint"; else fail "连接失败-exit3+hint" "exit=$e"; fi

# 8 超时 → exit 4（nc 收而不答）
(nc -l -p 19299 >/dev/null 2>&1 &) ; sleep 0.4
"$MDUT" --task g add mysql --host 127.0.0.1 --port 19299 --timeout 1 >"$WORK/o8" 2>/dev/null; e=$?
kill %1 2>/dev/null || pkill -f 'nc -l -p 19299' 2>/dev/null || true
if [ $e -eq 4 ] && python3 -c "
import json;d=json.loads(open('$WORK/o8').read())
assert d['ok'] is False and ('超时' in d['error'] or 'timeout' in d['error'].lower())"; then pass "超时-exit4"; else fail "超时-exit4" "exit=$e"; fi

# 9 写锁竞争 → exit 5（python fcntl 持锁，与 Java FileChannel 同命名空间）
"$MDUT" --task g add redis --host 127.0.0.1 --port 16379 --timeout 1 >/dev/null 2>&1 || true
python3 -c "
import fcntl,time,sys
f=open('$MDUT_TASKS_ROOT/g/.lock','w')
fcntl.lockf(f, fcntl.LOCK_EX)
time.sleep(2.5)" 2>/dev/null &
HOLDER=$!
sleep 0.5
"$MDUT" --task g clean --id 1 >"$WORK/o9" 2>/dev/null
EXPECT_EXIT=$?
wait $HOLDER 2>/dev/null
expect_env "写锁竞争-exit5" 5 "$WORK/o9" "d['ok'] is False and '锁' in d['error']"

# 10 task 隔离：g2 不可见 g 的连接
"$MDUT" --task g2 list >"$WORK/o10" 2>/dev/null
expect_env "task隔离" 0 "$WORK/o10" "d['data']['conns']==[] and d['task']=='g2'"

# 11 审计落账
if [ -s "$MDUT_TASKS_ROOT/g/logs/audit.jsonl" ] && python3 -c "
import json
line=open('$MDUT_TASKS_ROOT/g/logs/audit.jsonl').read().strip().splitlines()[-1]
d=json.loads(line)
assert d['task']=='g' and 'exit' in d and 'argv' in d"; then pass "审计jsonl"; else fail "审计jsonl" ""; fi

if [ "$FAST" -eq 1 ]; then
    say "== LAB 用例跳过（--fast） =="
else
    say "== LAB 用例（依赖本机实例） =="
    # 12 并发 add 同 task（WAL/busy_timeout；需真实可连通目标才能留行）
    if timeout 2 bash -c 'echo -e "PING\r" | nc -q1 -w1 127.0.0.1 6379' 2>/dev/null | grep -q "NOAUTH\|PONG"; then
        CT="conc-$$-$(date +%s)"   # PID 会跨运行复用，必须叠加时间戳防任务名碰撞
        for i in 1 2 3; do "$MDUT" --task "$CT" add redis --host 127.0.0.1 --port 6379 --pass mirrorstrike >/dev/null 2>&1 & done
        wait
        "$MDUT" --task "$CT" list >"$WORK/o12" 2>/dev/null
        expect_env "并发add-WAL" 0 "$WORK/o12" "len(d['data']['conns'])==3"
        rm -rf "$MDUT_TASKS_ROOT/$CT"
    else
        blocked "并发add-WAL" "本机 redis 不可达"
    fi
    # 13 本机 redis 可达时：add→info→delete 全链（非破坏）
    if timeout 2 bash -c 'echo -e "PING\r" | nc -q1 -w1 127.0.0.1 6379' 2>/dev/null | grep -q "NOAUTH\|PONG"; then
        ID=$("$MDUT" --task labtc add redis --host 127.0.0.1 --port 6379 --pass mirrorstrike 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
        "$MDUT" --task labtc info --id "$ID" >"$WORK/o13" 2>/dev/null; e=$?
        if [ $e -eq 0 ] && python3 -c "
import json;d=json.loads(open('$WORK/o13').read())
assert d['ok'] and d['data']['version'] and d['data']['arch_bits']"; then pass "TC1-redis-info"; else fail "TC1-redis-info" "exit=$e"; fi
        "$MDUT" --task labtc delete "$ID" >/dev/null 2>&1
    else
        blocked "TC1-redis-info" "本机 redis 不可达"
    fi

    # 14 本机 pg 可达时：add→info→sql→delete（只读）
    if timeout 2 bash -c 'nc -z -w1 127.0.0.1 5432' 2>/dev/null; then
        "$MDUT" --task labtc2 add postgresql --host 127.0.0.1 --port 5432 --user postgres --pass mirrorstrike --db mirrorstrike >"$WORK/o14a" 2>/dev/null; e=$?
        if [ $e -eq 0 ]; then
            ID=$(python3 -c "import json;print(json.loads(open('$WORK/o14a').read())['id'])")
            "$MDUT" --task labtc2 info --id "$ID" >"$WORK/o14b" 2>/dev/null
            if python3 -c "
import json;d=json.loads(open('$WORK/o14b').read())
assert d['ok'] and d['data']['route'] in ('low','udf','cve')"; then pass "TC2-pg-info选路"; else fail "TC2-pg-info选路" "$(cat "$WORK/o14b")"; fi
            "$MDUT" --task labtc2 sql --id "$ID" "select 1" >/dev/null 2>&1
            [ $? -eq 0 ] && pass "TC2-pg-sql" || fail "TC2-pg-sql" ""
            "$MDUT" --task labtc2 delete "$ID" >/dev/null 2>&1
        else
            fail "TC2-pg-add" "exit=$e"
        fi
    else
        blocked "TC2-pg" "本机 postgresql 不可达"
    fi
fi

say ""
say "== 结果: PASS=$PASS FAIL=$FAIL BLOCKED=$BLOCKED =="
if [ ${#FAILED_CASES[@]} -gt 0 ]; then
    say "失败用例: ${FAILED_CASES[*]:-}"
    exit 1
fi
exit 0
