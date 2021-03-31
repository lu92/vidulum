package com.multi.vidulum.shared.cqrs;


import com.multi.vidulum.shared.cqrs.commands.Command;
import com.multi.vidulum.shared.cqrs.commands.CommandHandler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CommandGateway {

    private final Map<Class<?>, CommandHandler<? extends Command, ?>> commandHandlers = new ConcurrentHashMap<>();

    public void registerCommandHandler(CommandHandler<? extends Command, ?> commandHandler) {

        Method handleMethod = extractHandlerMethod(commandHandler);

        Class<?> commandType = handleMethod.getParameterTypes()[0];

        commandHandlers.put(commandType, commandHandler);
    }

    private Method extractHandlerMethod(CommandHandler<? extends Command, ?> commandHandler) {
        String handlerMethod = CommandHandler.class.getMethods()[0].getName();
        return Arrays.stream(commandHandler.getClass().getMethods())
                .filter(method -> handlerMethod.equalsIgnoreCase(method.getName()) && method.getGenericParameterTypes()[0] != Command.class)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid parameter!"));
    }


    public <T extends Command, R> R send(T command) {
        if (commandHandlers.containsKey(command.getClass())) {
            CommandHandler<T, R> commandHandler = (CommandHandler<T, R>) commandHandlers.get(command.getClass());
            return commandHandler.handle(command);
        } else {
            throw new IllegalArgumentException();
        }
    }
}
