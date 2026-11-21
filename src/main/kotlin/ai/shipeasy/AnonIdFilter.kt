package ai.shipeasy

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Servlet [Filter] that mints the shared `__se_anon_id` bucketing cookie. For any
 * request without a valid `__se_anon_id` cookie it mints a UUIDv4, exposes it for
 * the request, and `Set-Cookie`s it on the response. Once installed,
 * gate/experiment evaluations with no explicit `user_id`/`anonymous_id`
 * automatically bucket on the cookie id — anonymous visitors get stable,
 * SSR/browser-consistent bucketing with no per-call wiring.
 *
 * Register it like any filter. With Spring Boot a default `FilterRegistrationBean`
 * already maps to all paths:
 *
 * ```kotlin
 * @Bean
 * fun shipeasyAnonId() = FilterRegistrationBean(AnonIdFilter())
 * ```
 *
 * `jakarta.servlet-api` is a `compileOnly` dependency — your servlet container
 * already supplies it at runtime, so this adds nothing to your deployment.
 * Non-servlet stacks can use [AnonId] directly instead.
 *
 * It also reads the browser SDK's `X-SE-Correlation` request header into the
 * request-local [SeeContext], so every `see()` fired while serving the request
 * carries that `correlation_id` — the token the platform joins on to link the
 * browser's error to the server exception behind it. A malformed token is
 * dropped, never sent.
 *
 * It also honors the signed DevTools override cookie (`se_ov`): the cookie is
 * verified once per request and, while the request runs, every `Client(user)`
 * read applies the overrides (programmatic `overrideFlag` still wins). Fail
 * closed — no key served by Shipeasy, a bad signature or an expired cookie mean
 * no overrides. Non-servlet stacks use [withRequestOverrides].
 */
class AnonIdFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val req = request as? HttpServletRequest
        val res = response as? HttpServletResponse
        if (req == null || res == null) {
            chain.doFilter(request, response)
            return
        }

        var id = req.cookies?.firstOrNull { it.name == AnonId.COOKIE && AnonId.isValid(it.value) }?.value
        if (id == null) {
            id = AnonId.mint()
            res.addHeader("Set-Cookie", AnonId.buildSetCookie(id, isSecure(req)))
        }
        AnonId.setCurrent(id)
        SeeContext.setCorrelationId(req.getHeader(SeeContext.CORRELATION_HEADER))
        // Signed DevTools overrides: verify the `se_ov` cookie once for this request.
        val prevOverrides = RequestOverrides.enter(RequestOverrides.verified(cookieHeader(req), req.getHeader(OverrideCookie.HEADER)))
        try {
            chain.doFilter(request, response)
        } finally {
            RequestOverrides.restore(prevOverrides)
            AnonId.clear()
            SeeContext.clear()
        }
    }

    private fun cookieHeader(req: HttpServletRequest): String? {
        val all = runCatching { req.getHeaders("Cookie")?.toList() }.getOrNull()
        return if (!all.isNullOrEmpty()) all.joinToString("; ") else req.getHeader("Cookie")
    }

    private fun isSecure(req: HttpServletRequest): Boolean {
        if (req.isSecure) return true
        val proto = req.getHeader("X-Forwarded-Proto") ?: return false
        return proto.substringBefore(",").trim().equals("https", ignoreCase = true)
    }
}
