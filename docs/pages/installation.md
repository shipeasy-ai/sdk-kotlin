# Installation

Server SDK for the JVM (Android-compatible). Distributed on Maven Central as
`ai.shipeasy:shipeasy-kotlin`.

## Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("ai.shipeasy:shipeasy-kotlin:0.8.0")
}
```

## Gradle (Groovy DSL)

```groovy
dependencies {
    implementation 'ai.shipeasy:shipeasy-kotlin:0.8.0'
}
```

## Maven

```xml
<dependency>
  <groupId>ai.shipeasy</groupId>
  <artifactId>shipeasy-kotlin</artifactId>
  <version>0.8.0</version>
</dependency>
```

## Runtime

- **JDK 17+** (uses `java.net.http.HttpClient`).
- Kotlin coroutines are used internally; `init()` is a `suspend` function — call
  it from a coroutine or wrap it in `runBlocking { }`.
- `jakarta.servlet-api` is a **`compileOnly`** dependency used by the optional
  `AnonIdFilter`; your servlet container supplies it at runtime, so it adds
  nothing to non-servlet deployments.

## Imports

```kotlin
import ai.shipeasy.configure
import ai.shipeasy.Client
import ai.shipeasy.Engine        // advanced / direct use, offline factories
import ai.shipeasy.see           // structured error reporting
```
