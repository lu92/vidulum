package com.multi.vidulum.shared.cqrs;


import com.multi.vidulum.shared.cqrs.queries.Query;
import com.multi.vidulum.shared.cqrs.queries.QueryHandler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class QueryGateway {
    private final Map<Class<?>, QueryHandler<? extends Query, ?>> queryHandlers = new ConcurrentHashMap<>();

    public <R> void registerQueryHandler(QueryHandler<? extends Query, R> queryHandler) {

        Method handleMethod = extractHandlerMethod(queryHandler);

        Class<?> queryType = handleMethod.getParameterTypes()[0];

        queryHandlers.put(queryType, queryHandler);
    }

    private Method extractHandlerMethod(QueryHandler<? extends Query, ?> queryHandler) {
        String handlerMethod = QueryHandler.class.getMethods()[0].getName();
        return Arrays.stream(queryHandler.getClass().getMethods())
                .filter(method -> handlerMethod.equalsIgnoreCase(method.getName()) && method.getGenericParameterTypes()[0] != Query.class)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid parameter!"));
    }


    public <T extends Query, R> R send(T query) {
        if (queryHandlers.containsKey(query.getClass())) {
            QueryHandler<T, R> queryHandler = (QueryHandler<T, R>) queryHandlers.get(query.getClass());
            return queryHandler.query(query);
        } else {
            throw new IllegalArgumentException();
        }
    }
}
