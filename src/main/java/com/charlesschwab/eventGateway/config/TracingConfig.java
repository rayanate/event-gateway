package com.charlesschwab.eventGateway.config;

import jakarta.servlet.Filter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Configuration
public class TracingConfig {

    public static final String TRACE_HEADER = "X-Trace-Id";

    @Bean
    public Filter traceFilter() {
        return new HttpFilter() {
            @Override
            protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
                try {
                    String trace = request.getHeader(TRACE_HEADER);
                    if (trace == null || trace.isBlank()) trace = UUID.randomUUID().toString();
                    MDC.put("traceId", trace);
                    response.setHeader(TRACE_HEADER, trace);
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove("traceId");
                }
            }
        };
    }

    @Bean
    public ClientHttpRequestInterceptor traceInterceptor() {
        return (request, body, execution) -> {
            String trace = MDC.get("traceId");
            if (trace != null) {
                request.getHeaders().add(TRACE_HEADER, trace);
            }
            return execution.execute(request, body);
        };
    }

    @Bean
    @Primary
    public RestTemplate tracedRestTemplate(ClientHttpRequestInterceptor traceInterceptor) {
        RestTemplate rt = new RestTemplate();
        rt.getInterceptors().add(traceInterceptor);
        return rt;
    }
}



