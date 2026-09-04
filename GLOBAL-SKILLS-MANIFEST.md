# 全局技能待装清单 / Global Skills — To Install

> 用途：列出**需要补充安装**到全局技能目录 `~/.agents/skills/` 的技能（增量清单，不含已装项）。
> 原则：一律取自公共源原版、不做本地修改；多机部署直接按本文拉取。
> 更新：2026-09-04（按讨论结论重写为增量版）。

**已装基线（19 个，无需重复安装）**：docx、pdf、pptx、xlsx、frontend-design、skill-creator、grill-me、grilling、handoff、resolving-merge-conflicts、teach、wizard、writing-beats、writing-for-agents、web-design-guidelines、find-skills、defuddle、obsidian-cli、obsidian-markdown。

---

## A. superpowers 工程方法论 —— 强烈建议：全装 14 个

> 取代 mattpocock 工程流（项目级 35 个仅作参考、可删）；跨运行时（Claude Code/Codex/Gemini/Copilot 等），SKILL.md 零 CC 耦合，官方认可 `~/.agents/skills/` 为跨运行时目录。

- 来源：`https://github.com/obra/superpowers`（MIT）
- 仓库内路径：`skills/<name>/`

| 技能 | 定位 |
|---|---|
| brainstorming | 苏格拉底式设计细化（写码前打磨） |
| using-git-worktrees | 隔离工作树 |
| writing-plans | 细粒度实现计划 |
| subagent-driven-development | 子代理驱动开发（两阶段评审） |
| executing-plans | 批量执行 + 人工检查点（无子代理时的降级路径） |
| test-driven-development | RED-GREEN-REFACTOR |
| systematic-debugging | 四阶段根因排查 |
| verification-before-completion | 完成前验证纪律 |
| requesting-code-review / receiving-code-review | 评审请求与反馈姿态 |
| dispatching-parallel-agents | 并行子代理 |
| finishing-a-development-branch | 分支收尾 |
| using-superpowers | 系统引导 |
| writing-skills | 技能写作（含测试方法论） |

```bash
git clone --depth 1 https://github.com/obra/superpowers "$TMP/sp"
for d in brainstorming dispatching-parallel-agents executing-plans finishing-a-development-branch receiving-code-review requesting-code-review subagent-driven-development systematic-debugging test-driven-development using-git-worktrees using-superpowers verification-before-completion writing-plans writing-skills; do
  cp -r "$TMP/sp/skills/$d" ~/.agents/skills/
done
```

## B. mattpocock 设计层补充 —— 建议装 2 个

> 依赖关系已满足：`grilling`（全局已装）为二者共同依赖；装上后与 grilling 形成「盘问 + 领域沉淀」trio。均无 CC 功能耦合。

- 来源：`https://github.com/mattpocock/skills`

| 技能 | 仓库内路径 | 作用 |
|---|---|---|
| domain-modeling | `skills/engineering/domain-modeling/` | 领域建模：术语表、ADR、CONTEXT.md（grill-with-docs 的依赖，纯方法论，零耦合） |
| grill-with-docs | `skills/engineering/grill-with-docs/` | 盘问 + 边沉淀 ADR/glossary（转发 grilling + domain-modeling） |

```bash
git clone --depth 1 https://github.com/mattpocock/skills "$TMP/mp"
cp -r "$TMP/mp/skills/engineering/domain-modeling" ~/.agents/skills/
cp -r "$TMP/mp/skills/engineering/grill-with-docs"   ~/.agents/skills/
```

## C. Anthropic 官方 — 产出层补充 —— 建议装 4 个

> 补 superpowers 不覆盖的「非代码产出」。已装 frontend-design / skill-creator，不重复。

- 来源：`https://github.com/anthropics/skills` → `example-skills` 插件（路径 `skills/<name>/`）

| 技能 | 仓库内路径 | 作用 |
|---|---|---|
| webapp-testing | `skills/webapp-testing/` | Web 应用端到端测试（前端日常刚需） |
| web-artifacts-builder | `skills/web-artifacts-builder/` | 构建交互式 Web artifact（原型/演示页） |
| canvas-design | `skills/canvas-design/` | 画布/视觉稿设计 |
| mcp-builder | `skills/mcp-builder/` | 编写/调试 MCP server（自建 MCP 时用） |
| discernment-nudge | `skills/discernment-nudge/` | 元技能：实质答复后追加核查追问（0 耦合，跨 agent 通用） |
| theme-factory | `skills/theme-factory/` | 产出物统一主题化（幻灯片/文档/HTML 落地页） |

```bash
git clone --depth 1 https://github.com/anthropics/skills "$TMP/anth"
for d in webapp-testing web-artifacts-builder canvas-design mcp-builder discernment-nudge theme-factory; do
  cp -r "$TMP/anth/skills/$d" ~/.agents/skills/
done
```

## D. 可选补充（按需，非必装）

| 技能 | 来源 | 何时装 |
|---|---|---|
| research | mattpocock `skills/engineering/research/` | 需要"写码前外部调研"的方法论时 |
| grilling 的显式入口 grill-me | 已装 | —（无需操作） |

---

## 部署注意事项

1. **同名冲突**：superpowers 无同名；B/C 组与宿主内置无冲突（frontend-design、skill-creator 等冲突项已在已装基线中，部署时勿覆盖宿主内置版本）。
2. **哈希校验**：每装一个技能记录 `SKILL.md` 的 SHA-256，便于多机对账。
3. **勿改原版**：本地定制走独立覆盖层；公共源拉取保持原样。
4. **方法论边界**：superpowers 与 mattpocock 工程流不同时进同一项目（项目级 mattpocock 仅参考）。

> 安装目标目录 `~/.agents/skills/`（本机即 `C:\Users\Administrator\.agents\skills\`）。
