# HANDOFF: Solips Project Memory

This document is the curated durable memory for the Solips project. It is read at task start or resumption and updated after meaningful decisions, completions, and before task handoffs or compaction.

---

## 1. Approved Decisions

- **Customization Architecture**:
  - Implemented repository-wide AI behavior customizations via Markdown files:
    - Root orchestration guidelines: [AGENTS.md](./AGENTS.md)
    - Project instructions: [.github/copilot-instructions.md](.github/copilot-instructions.md)
    - Curated memory: [HANDOFF.md](./HANDOFF.md)
    - Custom agent manifests: [.github/agents/](.github/agents/)
- **Approved Delegation Matrix & Exact Models**:
  - **Planning**: [.github/agents/planning.agent.md](.github/agents/planning.agent.md) using model `9router-github-copilot/planning` with tools `[read, search]`.
  - **Coding**: [.github/agents/coding.agent.md](.github/agents/coding.agent.md) using model `9router-github-copilot/coding` with tools `[read, search, edit, execute]`.
  - **Research**: [.github/agents/research.agent.md](.github/agents/research.agent.md) using model `9router-github-copilot/research` with tools `[read, search, web]`.
  - **Hard UI**: [.github/agents/hard-ui.agent.md](.github/agents/hard-ui.agent.md) using model `9router-github-copilot/hard-UI` with tools `[read, search, edit, execute]`.
- **Delegation Rules**:
  - Coordinator must strictly delegate specialist tasks to these exact models/agents.
  - **No Silent Substitution**: If a model or delegate is unavailable at runtime, the coordinator must not substitute a model or execute the specialist work; it must report a blocker to the user.
  - Coding agent implements backend and tests, and routes any UI work back to the coordinator (never implements UI itself).
  - All custom agents have `agents: []` to prevent nested delegation.
- **User Collaboration ("Grill Me")**:
  - Challenge assumptions before new features or fixes requiring decisions.
  - Ask focused questions one at a time; do not batch questions or invent approvals.
  - Pause implementation until approved.
  - Respect explicit user opt-outs and record assumptions here.
- **Context Management**:
  - Coordinator owns writes to `HANDOFF.md`; delegates return context deltas.
  - Keep this document concise, prune stale facts, and never store secrets or full chat archives.

---

## 2. Assumptions

- **Host Runtime Support**:
  - Markdown manifests follow the VS Code Custom Agents specification.
  - Runtime availability of the exact `9router-github-copilot/*` models depends on the host routing environment and cannot be statically verified during repository authoring.
- **Technology Stack**:
  - Java 21 LTS, Spring Boot 4.1.1 parent in `core-service`.
  - MySQL support via Spring JDBC and MySQL Connector/J; credentials supplied via environment variables (`DB_USERNAME`, `DB_PASSWORD`).
  - Approved default database URL: `jdbc:mysql://localhost:3306/core_service`, overridable with `DB_URL`. No automatic schema or database creation.
- **User Work Preservation**:
  - Existing uncommitted/unstaged modifications in `README.md`, `core-service/pom.xml`, and `core-service/src/main/resources/application.yaml` are intentional user work and must remain untouched.

---

## 3. Current State

- **MySQL Migration (2026-10-21)**: PostgreSQL driver replaced with MySQL Connector/J; datasource uses the approved URL and environment credentials. Java 21 and Spring JDBC retained.
- **Migration Verification**: `CoreServiceApplicationTests` passed (1 test, no failures/errors). TCP probes to localhost port 3306 failed; live authentication and `SELECT 1` remain unverified. Start MySQL and ensure `core_service` exists before verifying a real connection. No database/user/schema mutations performed.

- **Customization Manifests**: Complete and validated on disk:
  - `D:\projects\Solips\AGENTS.md`
  - `D:\projects\Solips\HANDOFF.md`
  - `D:\projects\Solips\.github\copilot-instructions.md`
  - `D:\projects\Solips\.github\agents\planning.agent.md`
  - `D:\projects\Solips\.github\agents\coding.agent.md`
  - `D:\projects\Solips\.github\agents\research.agent.md`
  - `D:\projects\Solips\.github\agents\hard-ui.agent.md`
- **Validation**:
  - File existence, relative path cross-links, YAML frontmatter syntax, tool alias arrays, model IDs, and `agents: []` declarations verified.
  - Existing user modifications in `README.md`, `core-service/pom.xml`, and `core-service/src/main/resources/application.yaml` preserved intact.
  - Git status checked (`git diff --check` passes with no whitespace errors or conflicts).
  - **Note on Runtime Discovery**: Files are structurally complete and validated; actual runtime agent discovery and model routing remain unverified until loaded in a supporting Copilot host environment.

---

## 4. Blockers

- *None currently.* (Any future runtime model discovery failures or unavailability must be logged here and escalated to the user rather than silently bypassed).

---

## 5. Next Actions

- Use the established workflow for upcoming tasks:
  - Coordinator reads `HANDOFF.md`.
  - For new features, initiate "Grill Me" clarification (one question at a time).
  - Delegate planning to Planning agent (`9router-github-copilot/planning`).
  - Delegate backend implementation/tests to Coding agent (`9router-github-copilot/coding`).
  - Delegate research to Research agent (`9router-github-copilot/research`).
  - Delegate UI/frontend implementation to Hard UI agent (`9router-github-copilot/hard-UI`).
  - Update `HANDOFF.md` upon completion or before context compaction.

---

## 6. Relevant Paths & Verification

- **Guidelines & Manifests**:
  - Root Guidelines: `AGENTS.md`
  - Copilot Instructions: `.github/copilot-instructions.md`
  - Durable Memory: `HANDOFF.md`
  - Planning Agent: `.github/agents/planning.agent.md`
  - Coding Agent: `.github/agents/coding.agent.md`
  - Research Agent: `.github/agents/research.agent.md`
  - Hard UI Agent: `.github/agents/hard-ui.agent.md`
- **Product Files (Preserved Unmodified)**:
  - Documentation: `README.md`
  - Core Service POM: `core-service/pom.xml`
  - Application Config: `core-service/src/main/resources/application.yaml`
- **Verification Commands**:
  - Git status verification: `git status`
  - Git diff whitespace/syntax check: `git diff --check`
