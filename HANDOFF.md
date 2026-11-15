# HANDOFF: Solips Project Memory

This document is the curated durable memory for the Solips project. It is read at task start or resumption and updated after meaningful decisions, completions, and before task handoffs or compaction.

---

## 1. Approved Decisions

- **Consent removal approved (2026-11-15)**: Supersedes consent-before-capture requirements below. Admin selects employee, opens camera, captures three samples and explicitly saves without employee confirmation. Remove consent UI/content, issuance API, token, signing secret requirement and new consent storage obligations; never fabricate consent. Preserve Admin authorization, image quality validation, revision conflicts, reenrollment/delete and existing embeddings. Any compatibility migration is documentation-only: no live schema execution or data deletion. Coding owns backend/tests/docs; Hard UI owns face client/dialog/styles/tests, concurrently against GET/PUT/DELETE contract with images plus expectedRevision and consent-free status response. Implementation completed in source: removed consent service/DTOs/API/config/response and UI receipt/confirmation gating. Added truthful enrollment actor metadata; documented compatibility migration leaving historical consent/sample data untouched. Backend 38 focused tests pass (coordinator verified persisted reports); frontend delegate reports 30 tests, build and lint pass. No live database migration, restart, or end-to-end webcam verification performed. Apply reviewed compatibility migration before deploying consent-free backend; deploy frontend/backend together. Legacy consent columns must become nullable and nullable enrolled_by_admin_id added. Earlier consent-based details below are historical.

- **Face enrollment scope (2026-11-08)**: User approved MySQL instead of PostgreSQL/pgvector; quality checks for exactly one face, face size, blur and illumination; three accepted images stored as separate embeddings, no raw image retention. Admin initiates enrollment and employee confirms versioned consent before capture; store server timestamp and Admin actor. Re-enrollment replaces old samples atomically only after new samples pass and persist. Face deletion removes embeddings and associated consent, returning unregistered status. Include Admin registration/status/re-enrollment/deletion UI. Recognition, attendance and liveness are out of scope. Implementation may be staged; no commits created yet. User approved available runtime Planning/Coding/Hard UI labels for this entire task instead of unavailable slash IDs.

- **Camera UI refinement (2026-11-08)**: User approved matching both login and admin with a paper/forest-green shell, dark video viewport, quiet borders, and no glow or simulated scanning. Explicitly approved runtime `Hard UI (9router-github-copilot)` substitution for unavailable slash model. User delegated functional choices; preserve existing camera controls without adding backend recognition or attendance.

- **Webcam Attendance Kiosk UI (2026-11-06)**: User approved dedicated fullscreen webcam attendance kiosk mode with quick-switch controls between Login and Kiosk. Only frontend UI/UX in scope (no backend recognition/AI API yet). Defined and invoked `Hard UI` specialist subagent for implementation adhering to Solips design tokens and baseline-ui standards.

- **Frontend bootstrap (2026-10-25)**: User approved only runnable React + TypeScript + Vite in new `frontend/` directory, no login/CRUD/API integration. Explicitly approved runtime `Hard UI (9router-github-copilot)` label as substitute for unavailable configured slash model for this task.

- **CRUD scope (2026-10-24)**: User approved Admin-only Employee/Department CRUD at `/api/employees` and `/api/departments`: POST, paginated GET, GET/PUT/DELETE `/{id}`. Deleted records are hidden and cannot be updated; no restore. Both use `deleted_at` (add to Department). Department deletion detaches all referencing employees; Employee deletion clears all referencing department managers; no physical deletion or employment status change. Code/email remain globally unique including deleted employees. Managers need not belong to departments they manage; one employee may manage several departments.
- **CRUD implementation defaults (2026-10-24)**: Coordinator-selected defaults, not separately user-approved: page 0/size 20/max 100, DTO-only responses in existing ApiResponse, create 201 and other success 200, repeated delete 404, required field validation, no case normalization, trimmed user text, full-replacement PUT, status defaults ACTIVE on create and required on update. Preserve existing JWT expiry/revocation behavior; add signed role claim and Admin authority conversion (old roleless tokens require login again). Use consistent pessimistic locks for assignment/deletion where feasible to avoid concurrent stale references; no live database/schema validation.

- **Employee/Department (2026-10-24)**: User approved runtime labels `Planning (9router-github-copilot)` / `Coding (9router-github-copilot)` for this task. Create entities and isolated tests only: generated UUID IDs, Lombok, Employee fields `code`, `fullName`, `email`, nullable `department`, string enum status `ACTIVE`/`INACTIVE`/`RESIGNED` defaulting to `ACTIVE`, nullable `Instant deletedAt`; Department fields `name` and nullable Employee `manager`. Employee code/email required and unique; full name and department name required. No API, repository, automatic soft-deletion/filtering, migrations, or database execution. Follow existing column-length defaults and use lazy references without cascades; no additional manager business rules in this entity-only scope.

- **Java conventions (2026-10-23)**: User requests Lombok for getter/setter/constructor boilerplate and layer-based packages (`entity`, `repo`, `service`, `controller`, `dto`, `config`, `exception`, `security`), not feature packages. Persisted in `.github/copilot-instructions.md` for subsequent prompts. Refactored JWT classes and test imports accordingly; preserved API/security behavior, no generated credential toString or entity equality changes.

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

- **RestClient startup fix (2026-11-15)**: Added Boot-managed `spring-boot-starter-restclient` to `core-service/pom.xml`; Boot 4's Web MVC starter alone did not provide the `RestClient.Builder` required by `FaceConfiguration`. Preserves injected builder and existing timeouts/API behavior. Added five isolated `FaceConfigurationTest` cases and updated `docs/face-enrollment.md`. Coding delegate ran 20 focused tests and 57 face-module tests successfully under Java 21; coordinator verified persisted reports (57 tests, zero failures/errors) and clean diff whitespace. No live application/MySQL startup or commits. Reload Maven in IntelliJ before restarting to refresh the runtime classpath.

- **Enrollment stage 1 / partial backend (2026-11-08)**: Python quality boundary implemented in `face-service/main.py` and `face_engine.py`, with protected `/enrollment/embed`, one-face/size/blur/illumination checks, upload limits and model metadata; delegate reports 24 offline fake-engine tests passed. See `docs/face-quality.md`. Java foundations added: enrollment/sample entities and repositories, DTOs, error handling, face configuration, consent receipt service, upstream client. Coordinator compiled Java successfully and discovered consent timestamp precision mismatch; specialist fixed canonical whole-second issuance and reports 11/11 consent tests pass. **Java foundation is incomplete and not deployment-ready**: enrollment controller/transaction orchestration, employee lifecycle/summary integration, persistence/client/security coverage, schema deployment guidance and all frontend enrollment UI remain outstanding. No MySQL startup/schema changes, no commits. Some delegate calls ended without reports; inspect actual diff rather than assume complete implementation. Python docs' suggested Java error mapping needs correction to frozen contract: 1010 upload,1011 media,1012 no face,1013 multiple,1014 size,1015 blur,1016 light,1017 consent,1018 upstream,1019 unavailable,1020 timeout.

- **Camera UI refinement (2026-11-08)**: Hard UI refined only `frontend/src/Kiosk.tsx` and `frontend/src/kiosk.css`, preserving existing uncommitted work. Reuses login/admin tokens, simple 44px controls and 6px radii, static alignment guide, compact responsive instructions, and honest camera-only status. Camera lifecycle and controls unchanged. Delegate reports focused lint, production build, and all 43 tests passed; coordinator confirmed no editor diagnostics in both files. Browser desktop/mobile rendering and actual webcam behavior remain unverified.

- **Webcam Attendance Kiosk UI (2026-11-06)**: Implemented dedicated facial attendance webcam kiosk UI in React 19 + TypeScript (`frontend/src/Kiosk.tsx`, `frontend/src/kiosk.css`). Features live webcam stream with device selection & mirror toggle, clean track teardown, real-time digital clock (`hh:mm:ss`, `tabular-nums`) and Vietnamese date, Solips FaceMark-inspired reticle HUD overlay with corner brackets & scanning indicator, permission error handling with retry guidance, and fullscreen toggle. Integrated seamless navigation switch between Kiosk and Login/Admin in `App.tsx` (supports `#kiosk` hash). All 43 tests, oxlint, and TypeScript production build passed with 0 errors/warnings.

- **Face Service Benchmark & Evaluation (2026-11-01)**: Implemented ace-service/benchmark.py and ace-service/evaluate.py. Benchmarked 4 InsightFace model configurations on CPU across 100 iterations (buffalo_l det 640: p50 550.6ms / RAM 453.0MB; buffalo_s det 640: p50 90.2ms / RAM 175.9MB). Evaluated verification accuracy on LFW dataset (103 subjects, 201 images, 383 genuine pairs, 19,717 impostor pairs). Added .gitignore rules for Python .venv, caches, and dataset directories.

- **Backend CORS (2026-10-25)**: Enabled Spring Security CORS with `/api/**` configuration for explicit localhost/127.0.0.1 origins on ports 5173 and 4173. Override via `app.cors.allowed-origins` / `APP_CORS_ALLOWED_ORIGINS`. Methods GET/POST/PUT/DELETE/OPTIONS, headers Authorization/Content-Type/Accept, credentials=false, maxAge=3600. No auth bypass or frontend/config-file edits. Added 18 isolated CORS tests; delegate reports all 42 CORS/auth/Admin MVC tests passed under Java 21, no editor diagnostics. Backend restart required to load configuration; live browser issue not reproduced. Relative frontend API calls through Vite dev proxy normally remain same-origin.

- **Admin frontend completed (2026-10-25)**: Added `frontend/src/Admin.tsx`, `admin.css`, bearer CRUD client `api.ts` and memory session helpers `session.ts`; login now automatically shows admin sidebar for exact Admin role, with blank Dashboard and Employee/Department CRUD. Other roles remain on login with notice. Tables, pagination, detail/create/edit/delete dialogs, nullable relation selectors traversing all pages, cross-resource refresh and empty-last-page recovery implemented. JWT decoding is presentation-only; backend remains authorization boundary. Expiry/401 clears session; 403 stays distinct; no token persistence. Coordinator reran build/lint and 43 mocked tests successfully; no editor diagnostics, diff whitespace check clean (line-ending warning only). No backend/database startup or browser visual/keyboard/live API verification; these remain pending. Supersedes earlier connection blocker.

- **Login frontend (2026-10-25)**: User requested self-designed simple Vietnamese face-attendance login UI connected to existing API, explicitly no redirect yet. Hard UI implemented responsive neutral/green layout, accessible username/password form with visibility toggle, loading/validation/sanitized errors, and same-page success/local logout. POST `/api/auth/login` uses existing ApiResponse token contract. Token stays memory-only, password clears on success; no actual camera auth or dashboard. Vite dev proxy `/api` defaults localhost:8080 with server-side API_PROXY_TARGET override; production needs same-origin reverse proxy. Delegate reports build/lint and 13 mocked API tests passed; coordinator found no frontend editor diagnostics. Browser/mobile interaction checks and live API login not verified; backend/database not started. Only frontend product files changed; existing work preserved.

- **Frontend initialized (2026-10-25)**: Official Vite React + TypeScript starter created in `frontend/` with React 19, Vite 8 and npm scripts `dev`, `build`, `lint`, `preview`. `npm install` succeeded (27 packages, 0 reported vulnerabilities), `npm run build` and `npm run lint` passed, no editor diagnostics. `node_modules/` and `dist/` ignored. No product UI/API integration or running dev server; browser rendering not yet smoke-tested. Existing dirty root README and untracked initializer files preserved.

- **JWT build recheck (2026-10-25)**: User corrected DepartmentRepository UUID typo. Coding recompiled all 33 production/18 test sources with Maven on Java 21; JwtServiceTest, AuthSecurityMvcTest and DataInitializerTest all passed (23 tests). No source changes required. Root IntelliJ `.idea` reportedly lacks Maven registration for `core-service/pom.xml`; nested core-service IDE config registers it. Recommend linking/reloading Maven and JDK21 in the IDE reporting errors; exact current IDE execution configuration remains unverified. This supersedes earlier whole-project compile blocker and isolated-only initializer validation limitation.

- **DataInitializer (2026-10-25)**: Added transactional `config/DataInitializer.java` ApplicationRunner, executed every startup. Creates missing `test_admin`/`test_manager`/`test_employee` with exact roles Admin/Manager/Employee and enabled=true; existing username records never modified. Uses injected PasswordEncoder; user explicitly requested public demonstration credentials overriding earlier environment-password suggestion. Do not record password here. Added five isolated tests (actual BCrypt matching, existing-account preservation, mixed creation, repeat-run idempotence, enabled defaults). Delegate reports five tests passed via surefire:test against isolated compiled dependencies, not a successful whole-project compile. No app/database startup. Full compilation blocked by user-edited `DepartmentRepository.java` unknown type `UUIDeeee`; preserved untouched, request permission before fixing.

- **Admin CRUD completed (2026-10-24)**: Added employee/department repositories, transactional services, validated create/update DTOs, scalar response/page DTOs, and controllers for approved endpoints. Department now has `deletedAt`; active-only reads/references, global code/email uniqueness, relationship detachment and soft deletion implemented. JWT now includes role and converter enforces `ROLE_Admin` on both CRUD route trees; signed-token MVC tests cover Admin access, other-role/roleless 403 and anonymous 401. Old roleless tokens require login again. Error codes 1005 not found, 1006 duplicate/integrity conflict, 1007 concurrent lock conflict; malformed requests 400. Bulk cleanup flushes before clearing persistence context so deletion timestamps are not discarded. Pessimistic reference/update locks protect assignments; deadlock/lock failures return sanitized 409 (client may retry), not automatic retries. README API docs updated preserving existing work. All 105 selected unit/MVC-slice tests passed on Java 21, persisted reports verified; no source diagnostics and `git diff --check` clean. No live database/application startup/schema execution; actual JPQL, transaction rollback and concurrent lock behavior remain unverified against MySQL. No unrelated user work reverted.

- **Employee/Department entities (2026-10-24)**: Added `entity/Employee.java`, `Department.java`, and `EmployeeStatus.java` per approved design, with UUID generation, required/unique employee code and email, required names, lazy nullable department/manager associations without cascades, string status default `ACTIVE`, and nullable `Instant deletedAt`. Lombok constructors/accessors only; no generated equality/toString. Added 15 isolated tests (8 Employee, 7 Department); all passed under Java 21 and persisted Surefire reports verified by coordinator. No source diagnostics; delegate reports `git diff --check` clean. No Spring context, database access, schema execution, APIs, repositories, or automatic soft-delete behavior. Existing unrelated work preserved.

- **ApiResponse factories (2026-10-23)**: User requested convenient `success(result)` / `error(ErrorCode)` methods. Added generic static factories and migrated controller/global advice/JWT handlers to them; existing Builder remains compatible. JSON and HTTP behavior unchanged, including null omission. README documents usage. All 26 focused response/exception/MVC tests passed on Java 21; persisted reports verified and `git diff --check` passed. No database/schema access.

- **Unified API responses (2026-10-23)**: User approved Builder/Lombok `ApiResponse<T>`, success code 1000 with result and no message, error code/message without result, and `ErrorCode` carrying application code/message/HTTP status. Added `AppException(ErrorCode)` extending RuntimeException. Applied to AuthController/AuthService, global exception advice, and JWT authentication/access-denied handlers while preserving HTTP statuses and bearer challenge headers. Error codes: 1001 invalid credentials (401), 1002 unauthenticated (401), 1003 access denied (403), 1004 invalid request (400), 9999 unexpected error (500). README updated without discarding existing edits. All 41 focused response/auth/JWT/config tests passed on Java 21; persisted Surefire reports verified and `git diff --check` passed. No database/schema access. Existing unrelated modifications preserved.

- **Local JWT environment (2026-10-23)**: Created ignored, untracked root `.env` per user request; never record its contents here. Native optional Spring config imports load dotenv properties from repository-root or core-service working directories. Existing SecurityConfig consumes JWT_SIGNING_KEY with OS environment override and unchanged validation. README documents usage. Twelve key/config-import tests and twenty auth/JWT tests passed without database access.

- **Lombok/layer refactor verification (2026-10-23)**: Clean targeted Maven run on Java 21 passed all 26 auth/JWT tests after moving classes and imports. `git diff --check` passed; no source diagnostics. No database/schema access. The three previously documented entity-contract test failures remain outside this refactor.

- **JWT security (2026-10-23)**: User approved POST `/api/auth/login` as the only public API, BCrypt authentication against existing users, disabled-account login rejection, HS256 access tokens lasting 3600 seconds, and stateless Bearer authentication elsewhere. No registration, refresh tokens, or role authorization. Already-issued tokens remain valid until expiration; account revocation checks are not implemented.
- **Runtime setup**: Supply `JWT_SIGNING_KEY` as Base64 encoding of at least 32 random bytes; absent/invalid/weak keys prevent startup. Existing users must have BCrypt password hashes. See README for endpoint contract. Datasource settings were not modified.
- **Related baseline repairs approved**: Fixed Lombok scope/annotation processing and adapted old UserTest construction to current entity API. No production reflection workaround or test compiler exclusion remains.
- **Verification**: Clean targeted Maven run using `C:\Users\manhv\.jdks\graalvm-jdk-21.0.7` compiled successfully. All 26 security tests passed; 5 of 8 User tests passed. Three existing entity-contract assertions still fail: null-role fallback, username in toString, and equals/hashCode semantics.0g
 Entity behavior was deliberately not changed. No MySQL/schema access. Default Java 27 fails Lombok compilation; use project Java 21. `git diff --check` passed.

- **User entity (2026-10-22)**: Added `entity/User.java` and `entity/Role.java`. Approved UUID auto-generation, required unique username, required password hash mapped to `password_hash`, string role with exact values Admin/Manager/Employee (default Employee), and boolean enabled (default true). Table: `users`.
- **Verification**: 8 isolated User tests passed; combined User/context run passed 9 tests. Delegate reports the existing Hibernate configuration created `users` in local MySQL database `solips` during context testing; no datasource configuration was changed for this task. Future context tests must disable schema writes or obtain approval before running against a persistent database.

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



- **Database safety (2026-10-24)**: Read-only planning inspection reports current application configuration has `ddl-auto: update`; starting the application or context tests may modify persistent MySQL schema. Do not start either for entity verification. Configuration also reportedly contains hardcoded credentials; values are not recorded here and configuration changes are outside this task.

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
