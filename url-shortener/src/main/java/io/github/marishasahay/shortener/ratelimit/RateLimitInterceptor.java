package io.github.marishasahay.shortener.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the rate limit to {@code POST} requests on the paths it is registered for. Rejections
 * are thrown as {@link RateLimitExceededException} so they are rendered by the same
 * {@code ProblemDetail} advice as every other error.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

  private final RateLimiter rateLimiter;
  private final ClientKeyResolver clientKeyResolver;

  public RateLimitInterceptor(RateLimiter rateLimiter, ClientKeyResolver clientKeyResolver) {
    this.rateLimiter = rateLimiter;
    this.clientKeyResolver = clientKeyResolver;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    if (!HttpMethod.POST.matches(request.getMethod())) {
      return true;
    }
    RateLimitDecision decision = rateLimiter.tryAcquire(clientKeyResolver.resolve(request));
    if (!decision.allowed()) {
      throw new RateLimitExceededException(decision.retryAfter());
    }
    return true;
  }
}
