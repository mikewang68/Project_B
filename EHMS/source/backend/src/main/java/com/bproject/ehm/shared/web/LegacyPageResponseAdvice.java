package com.bproject.ehm.shared.web;

import com.bproject.ehm.shared.page.PageResult;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.UriComponentsBuilder;

@RestControllerAdvice
public class LegacyPageResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return PageResult.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof PageResult<?> page)) return body;
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        if (query.containsKey("page") || query.containsKey("size")) return body;

        response.getHeaders().add("Deprecation", "true");
        response.getHeaders().add("X-EHM-Compatibility", "legacy-list; add ?page=0&size=50 for page metadata");
        return page.content();
    }
}
