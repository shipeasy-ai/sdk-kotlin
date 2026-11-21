Honor signed-in DevTools overrides on server reads. Assumes `configure()` ran at
startup — see [Installation](../../pages/installation.md).

### Servlet stacks (Spring Boot, Jetty, Tomcat)

```kotlin
// Register once. The same filter mints the __se_anon_id cookie and scopes the
// verified `se_ov` overrides to each request (cleared when the request ends).
@Bean
fun shipeasyRequest() = FilterRegistrationBean(AnonIdFilter())

// anywhere in the request — construct once per callsite
val flags = Client(currentUser)
flags.getFlag("{{FLAG_KEY}}")   // true/false as overridden in DevTools, else normal
```

### Any other stack (Ktor, http4k, Javalin, coroutines)

```kotlin
import ai.shipeasy.withRequestOverrides

// cookieHeader: the raw `Cookie` request header (or just the se_ov value).
// Verified once on entry; cleared on exit. Reads inside the block see the overrides.
withRequestOverrides(call.request.headers["Cookie"]) {
    val flags = Client(currentUser)
    flags.getFlag("{{FLAG_KEY}}")
}
```

### Dev stacks: unsigned URL overrides

```kotlin
// Non-production only. Ignored (with one warning) when the process is production.
configure(apiKey = System.getenv("SHIPEASY_SERVER_KEY"), devOverrides = true)
```

### API on a sibling subdomain

```kotlin
// Second argument: the X-Shipeasy-Overrides header (cookie-header syntax). Cookie wins per name.
withRequestOverrides(call.request.headers["Cookie"], call.request.headers["X-Shipeasy-Overrides"]) {
    Client(currentUser).getFlag("{{FLAG_KEY}}")
}
```
