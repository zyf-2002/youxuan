# Repository Guidelines

## Project Structure & Module Organization
This repository is a single-module Spring Boot service built with Maven and Java 8. Application code lives under `src/main/java/com/hmdp`, organized by responsibility: `controller`, `service` and `service/impl`, `mapper`, `entity`, `dto`, `config`, `interceptor`, `listener`, and `utils`. Runtime configuration and SQL/assets are in `src/main/resources`, including `application.yaml`, `mapper/`, `db/hmdp.sql`, and Redis Lua scripts. Tests live in `src/test/java/com/hmdp`. The `target/` directory is build output and should not be edited manually.

## Build, Test, and Development Commands
Use Maven from the repository root:

- `mvn spring-boot:run` starts the app on port `8081`.
- `mvn test` runs the JUnit 5 test suite.
- `mvn clean package` compiles, tests, and builds the runnable jar under `target/`.
- `mvn -DskipTests package` builds faster when you only need a local artifact.

The app expects local MySQL, Redis, and RabbitMQ instances matching `src/main/resources/application.yaml`.

## Coding Style & Naming Conventions
Follow the existing Java style: 4-space indentation, one top-level class per file, and `UpperCamelCase` for classes. Use package names in lowercase and keep Spring stereotypes aligned with the current layering, for example `ShopController`, `IShopService`, `ShopServiceImpl`, `ShopMapper`. Prefer constructor or field injection patterns already used in nearby code, and keep DTO/entity names explicit rather than abbreviated. Reuse MyBatis-Plus and Hutool utilities already present before adding new framework abstractions.

## Testing Guidelines
Tests use Spring Boot Test with JUnit 5; some controller tests also use `MockMvc`. Name new tests `*Test` and place them in the matching `com.hmdp` package. Favor focused integration tests for cache, voucher-order, and login flows, since much of the behavior depends on Redis, MySQL, and MQ integration. Run `mvn test` before opening a PR, and call out any tests that require seeded data from `db/hmdp.sql`.

## Commit & Pull Request Guidelines
Git history is not available in this workspace snapshot, so no repository-specific commit convention could be verified. Use short, imperative commit subjects such as `Fix voucher stock check` or `Add Redis cache preload test`. Pull requests should describe the behavioral change, list any schema or config updates, include API examples or screenshots for controller changes, and note how the change was validated locally.

## Security & Configuration Tips
Keep local secrets and hostnames out of commits. Move environment-specific values from `application.yaml` to external overrides when possible, and never commit production credentials, exported tokens, or generated files from load-testing utilities.

Local file reference rules (strict):
- Do not use local-file Markdown/URL links (e.g., `[x](...)`, `file:///...`).
- Only use pure references inside single backticks: `@path[:line[:column]]`.
- Use `/` in paths (including Windows), e.g., `@D:/repo/src/main.cpp:128`; relative paths use the same format, e.g., `@src/main.cpp:128`.
- Write explanations in normal prose; keep file refs as standalone `@...`; if multiple files are mentioned, output multiple separate `@...` refs.
