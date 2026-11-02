# Spring Boot upgrade log (finding M5)

Date: 2026-10-23. Branch `Testing`. Not committed. Result: **Spring Boot 3.3.5 -> 4.1.2**, fully green
(`mvn verify`: 221 unit + 151 integration tests, 0 failures, 0 errors, 0 skipped). Integration tests ran only against
`walletdb_test`; `walletdb` and `walletdb_old_backup_20261021` were not touched; no app server was started.

## Why 4.1.2

Versions on Maven Central (starter-parent): 3.5.0-3.5.16, 4.0.0-4.0.9, 4.1.0-4.1.2 (4.2.0 only M/RC). Support dates were read
from `https://api.spring.io/projects/spring-boot/generations` (spring.io data, fetched today):

| Line | OSS support end |
|------|-----------------|
| 3.3.x | 2025-06-30 (current version, long out of support) |
| 3.4.x | 2025-12-31 |
| 3.5.x | **2026-06-30 (also already out of OSS support today)** |
| 4.0.x | 2026-12-31 (ends in about 2 months) |
| 4.1.x | 2027-07-31 |

So 3.5.x is not a valid end state: only 4.0.x and 4.1.x are supported today, and 4.1 has by far the longest remaining
window. Newest GA patch of 4.1 = 4.1.2.

Staged approach followed:
1. Stage 1, 3.5.16 (only a pom version change): `mvn verify` green with **no code changes** (221 + 151). Only deprecation
   warnings (`@MockBean`).
2. Stage 2, 4.1.2: needed a handful of small, mechanical changes (below); also fully green. 3.5.16 was therefore not kept.
   The tree is on ONE version (4.1.2).

## Dependency versions (from `mvn dependency:tree`)

| Component | Before (3.3.5) | Intermediate (3.5.16) | After (4.1.2) |
|-----------|----------------|-----------------------|---------------|
| spring-boot | 3.3.5 | 3.5.16 | 4.1.2 |
| spring-framework (spring-core) | 6.1.14 | 6.2.19 | 7.0.10 |
| spring-security | 6.3.4 | 6.5.11 | 7.1.2 |
| spring-data-jpa | 3.3.5 | 3.5.13 | 4.1.2 |
| tomcat-embed-core | 10.1.31 | 10.1.55 | 11.0.27 |
| hibernate-core | 6.5.3.Final | 6.6.53.Final | 7.4.13.Final |
| hibernate-validator | 8.0.1.Final | 8.0.3.Final | 9.1.5.Final |
| jackson (databind/core) | 2.17.2 | 2.21.4 | tools.jackson 3.1.7 (Boot/MVC); com.fasterxml 2.21.7 stays, runtime only, pulled by jjwt-jackson |
| pgjdbc (postgresql) | 42.7.4 | 42.7.11 | 42.7.14 |
| jjwt | 0.12.6 | 0.12.6 | 0.12.6 (unchanged, see below) |
| logback-classic | 1.5.11 | 1.5.34 | 1.5.38 |

jjwt stays at 0.12.6: it is not managed by the Boot BOM, it works unchanged on all three lines (JwtServiceTest and all
auth ITs green), and 0.13.0 exists but is not needed for the upgrade. pgjdbc comes from the BOM (no pin).
Hibernate 7 and Tomcat 11 (Servlet 6.1) came in with the BOM; no entity or schema change was needed.

## Changes (file: before -> after)

1. `pom.xml`
   - parent version `3.3.5` -> `4.1.2`.
   - added test dependency `org.springframework.boot:spring-boot-starter-webmvc-test` (scope test). In Boot 4 the MockMvc test
     support (`@WebMvcTest`, `@AutoConfigureMockMvc`) moved out of `spring-boot-test-autoconfigure` into this module.
     Test scope only; nothing is added to the runtime artifact.
2. `src/main/java/com/example/wallet/exception/GlobalExceptionHandler.java`
   - import `org.springframework.data.mapping.PropertyReferenceException` -> `org.springframework.data.core.PropertyReferenceException`
     (class moved in Spring Data 4). Same handler, still 400 "Invalid sort parameter".
3. `src/main/java/com/example/wallet/security/SecurityConfig.java`
   - `new DaoAuthenticationProvider(); p.setUserDetailsService(uds);` -> `new DaoAuthenticationProvider(uds);`
     (Spring Security 7 removed the no-arg constructor and the setter). The empty pre-check, the post-check that throws
     `DisabledException` after the password comparison, and the encoder are unchanged, so the disabled-login timing behavior is preserved.
   - NEW bean `jwtAuthFilterRegistration` (`FilterRegistrationBean<JwtAuthFilter>`, `setEnabled(false)`). See "Behavior differences".
4. Tests (no assertion weakened, none deleted):
   - `controller/ApiWebLayerTest`: `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` ->
     `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`; `@MockBean` (removed in Boot 4) -> `@MockitoBean`
     (`org.springframework.test.context.bean.override.mockito`).
   - `it/ItBase`: `AutoConfigureMockMvc` import moved the same way; the injected mapper changed from
     `com.fasterxml.jackson.databind.ObjectMapper` to `tools.jackson.databind.json.JsonMapper` (Boot 4 auto-configures only the
     Jackson 3 mapper, so the Jackson 2 `ObjectMapper` bean no longer exists; all 142 non-trivial ITs failed at startup until this was changed).
     `JsonNode` import -> `tools.jackson.databind.JsonNode` in `ItBase`, `DepositRequestIT`, `TransactionHistoryIT`.
   - `exception/GlobalExceptionHandlerTest`: imports of `PropertyReferenceException` and `TypeInformation` moved to `org.springframework.data.core`.
   - `it/SecurityRound2IT`: Jackson 3 renamed `JsonNode.fieldNames()` (iterator) to `propertyNames()` (collection);
     `assertThat(body(r).fieldNames()).toIterable().containsExactly(...)` -> `assertThat(body(r).propertyNames()).containsExactly(...)`.
     The assertion (the error body has exactly timestamp, status, error in that order) is unchanged.
5. `README.md` line 3: "Spring Boot 3.3" -> "Spring Boot 4.1". No other README command changed.

No property renames were needed: `spring.servlet.multipart.enabled`, `spring.jpa.*`, `spring.sql.init.*`,
`spring.data.web.pageable.*`, `server.address` all still bind (multipart 415 tests, paging cap tests green).
`META-INF/spring.factories` with the `FailureAnalyzer` still works on Boot 4 (JwtSecretStartupIT / JwtSecretStartupTest green).
`WalletRepositoryCustomImpl` (`em.refresh`, `PESSIMISTIC_WRITE`) is unchanged and the concurrency ITs are green on Hibernate 7.

## Behavior differences found

- **JwtAuthFilter registered twice (only exposed by Boot 4's MockMvc test slice).** `JwtAuthFilter` is a `@Component`, so Boot also
  registers it as a plain servlet filter in addition to its place inside the security chain. In `@WebMvcTest` on Boot 4 / Security 7 the
  outer instance ran first and set the authentication; the chain's `SecurityContextHolderFilter` then installed a fresh context
  and the inner copy was skipped (OncePerRequestFilter), so every authenticated request got 401 (21 of 27 `ApiWebLayerTest` tests).
  Fix: disable the servlet-level registration, so the filter runs only inside the chain, where it was always intended to run
  (the SecurityConfig javadoc on the ERROR dispatch assumes this). This is not a weakening: nothing is authenticated that was not before.
  The full-stack ITs passed even without this change, so production behavior was not affected by it; the change makes the test slice and the app consistent.
- **JSON stack is now Jackson 3** (`tools.jackson`) for request/response bodies. All tests on response shapes pass unchanged
  (error body key order, no idempotency key leak, BigDecimal amounts, paging). jjwt keeps using its own Jackson 2 (2.21.7) only to
  encode/decode token JSON. Since `com.fasterxml` Jackson 2 is now only a runtime transitive of jjwt-jackson, new code must
  not use it for API payloads.
- The startup warning "Global AuthenticationManager configured with an AuthenticationProvider bean. UserDetailsService beans will not be used..."
  exists on 3.3 too (we supply our own provider on purpose); unchanged.
- No compiler warnings in main code on 4.1.2.

Security behaviors re-confirmed by the existing tests on 4.1.2 (all green): JWT secret fail-fast (JwtSecretStartupIT/Test),
disabled-login same 401 and timing (AuthServiceTest, SecurityConfigProviderTest, ApiWebLayerTest, SecurityFixesIT), 72-byte password rule
(DtoValidationTest, ApiWebLayerTest), 404 for foreign ids (AuthAndAccessIT, ApiWebLayerTest), sort whitelist and page/size limits
(TransactionHistoryIT, DepositRequestIT, ApiWebLayerTest), multipart 415 (SecurityRound2IT / SecurityFixesIT), /error handling
(ContainerErrorIT on a real embedded Tomcat 11), NUL/format-char validation (DtoValidationTest, SecurityRound2IT).
(Test-class attribution is from the test names and layout; I did not re-map each assertion.)

## Test results

| Command | Result |
|---------|--------|
| baseline `mvn verify` on 3.3.5 | 221 unit + 151 IT, 0 failures |
| `mvn verify` on 3.5.16 (pom change only) | 221 + 151, 0 failures |
| `mvn verify` on 4.1.2 (final) | 221 unit + 151 integration, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS |

Intermediate failures on 4.1.2 (all fixed above): 5 main compile errors, then test compile errors (test-slice packages,
`@MockBean`, data package moves), then 21 `ApiWebLayerTest` 401s, then 142 IT startup errors (Jackson 2 `ObjectMapper` bean),
then one `fieldNames` compile error.

## Risks / not verified

- CVE check: no OWASP/NVD scan was run (needs an NVD key / long database download). From my own knowledge, UNVERIFIED: the old versions
  (Tomcat 10.1.31, Security 6.3.4, Framework 6.1.14, pgjdbc 42.7.4, Jackson 2.17.2) have published CVEs which newer patch
  releases fix. I could not confirm that the new versions (4.1.2 and its BOM) are free of known CVEs, as they are newer than my own knowledge.
  Run `mvn org.owasp:dependency-check-maven:check` with an NVD key, or an SCA tool in CI.
- Support dates come from the api.spring.io generations data fetched today; not cross-checked on the spring.io web page.
  4.0.x ends 2026-12-31 and 4.1.x 2027-07-31, so the move to 4.2 (initial release listed as 2026-11-30) will be due before mid 2027.
- The official migration guides were not fetched as documents; the changes were found by compiling and testing. Every breaking change
  this code touches surfaces at compile or test time, and 372 tests are green, but untested runtime paths could still differ
  (for example `ddl-auto=validate` against the existing dev `walletdb` was NOT run; it ran against `walletdb_test` only, which
  uses the same schema.sql).
- The real application was not started manually on 8081; ContainerErrorIT starts a real embedded Tomcat and passed.
- Tomcat 11 needs Servlet 6.1 if the app were ever deployed as a WAR (it is a jar with embedded Tomcat now).
- Frontend, TEST_FINDINGS.md and SECURITY_TEST_REPORT.md untouched.

## What staying on 3.5.x would have meant / what 4.x required (already done)

Staying on 3.5.16 needs no code change but is out of OSS support since 2026-06-30. The 4.x migration items were:
test-only starter `spring-boot-starter-webmvc-test` and new package names for `@WebMvcTest`/`@AutoConfigureMockMvc`, `@MockBean` ->
`@MockitoBean`, `PropertyReferenceException`/`TypeInformation` moved to `org.springframework.data.core`, `DaoAuthenticationProvider`
constructor injection, Jackson 3 in tests (`tools.jackson`, `fieldNames` -> `propertyNames`), and disabling the duplicate servlet
registration of `JwtAuthFilter`. Next to watch: Spring Boot 4.2, and jjwt 0.13.x when convenient.
