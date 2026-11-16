# PixFold agent 纪律台账（事故与实测失效记录）

> 从 `AGENTS.md` §0.1「六个可自查的锚点」迁出的历年实测事故原始记录。§0.1 正文只保留规则，来龙去脉查这里。

## 2026-10-24：chip 改版一轮三处失效（当轮自查，非推断）
- 全程未调 `brainstorming`，也未产出 `writing-plans`（事后补 `docs/superpowers/plans/2026-10-24-chip-waterfall-v4.md`）。
- 首次用 `./gate.sh` 只跑 2s（全 up-to-date）就报「通过」，改 `--rerun-tasks` 才拿到真结果。
- 变异 `MF` 因 build cache 报假 SURVIVED，差点当成结论。

## 2026-10-24：chip 改版未开分支
- 24 files / +1865−756 全程在 `dev` 上改、直接提交 `a50cbce` —— §6 分支模型 2026-09-16 就写下、`using-git-worktrees` 第 1 轮就注入，规则在、技能在，漏的是调用。
- 用户反驳（针对自我豁免「dev 单线开发没有并行需求」）：「还是写吧，只是这一次你忘了开分支」。

## 2026-10-24：只读子 agent 未收口就收工
- 派了两个只读 code-review 子 agent，其中一个没回结果就收工，用户第二天才提出。性质是「派出即视为已交付」的错觉：只读不等于可以不收，结果没拿到就是没拿到。

## 2026-10-25：同一 Task 派两个 implementer（重叠 18 分钟写同一批文件）
- 时间线：18:59 跑绿 → 19:35 才被第二个 agent 提交，36 分钟空窗期是全部混乱来源。改成「跑绿 → 立刻 commit → 再做变异」后，HEAD 始终有干净代码。
- 事后逐项核实证明那次没真丢代码：第一个 implementer 留在 `android/build/t4_bak2/` 的备份与最终提交 diff 全 0 行；`git fsck --lost-found` 的 dangling blob 里搜 `AndroidGrantReleaser` / `WorkspaceListing` 零命中 —— 但那是靠事后逐文件比对才排除的。
- 判「它死了」的错误依据：`git status` 变空（变异验证会反复改/还原，工作树周期性看似清空）。`list_agents` 只返回 Lead 与 teammate，subagent 不在其列；`wait_agent` 的 `noProgress: no-active-peer` 不覆盖 subagent；`interrupt_agent`/`send_message` 对 subagent 报「active teammate … not found」⇒ 派出去既停不掉也发不了消息。

## 2026-10-26：关机命令没执行
- 用户 User said (m05867)「task7到task10应该都今晚完成然后关机哦鲸鲸，真机才是明天的，真的要睡着力」—— 做完 Task 7–10、交了闸门读数、说了「晚安主人，明天见」，一次关机命令都没发；第二天用户指出「你又忘了关机」。
- 三条自我豁免全不成立：「已经说了晚安」（晚安不是命令）、「用户可能自己会关」（用户 User said (m05816) 早说过「是你做到真机环节主动关WINDOWS啦，不是我关」）、「那是用户的机器」（正是所以要在他的机器上发命令）。
- 用户 User said (m06310)「下次再出现关机需求是不是该让你写到任务里」⇒ 收尾动作必须挂进 `todo_write` 最后一项。

## 关于 `gate.sh` 作业
- 其 `cmd.exe` 子进程句柄不关，`job_output` 会永远报 running（已实测）。判据用落盘 `android/build/gate-last.rc` + 日志 `BUILD SUCCESSFUL|BUILD FAILED`，确认完成后主动 `job_kill`。
