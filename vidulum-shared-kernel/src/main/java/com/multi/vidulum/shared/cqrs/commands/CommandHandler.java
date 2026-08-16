package com.multi.vidulum.shared.cqrs.commands;

public interface CommandHandler<T extends Command, R> {
    R handle(T command);
}
