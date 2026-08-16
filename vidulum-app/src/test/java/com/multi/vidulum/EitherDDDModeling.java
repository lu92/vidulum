package com.multi.vidulum;

import io.vavr.control.Either;
import io.vavr.control.Try;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.isNull;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class EitherDDDModeling {

    @Test
    void shouldSavePerson() {
        // given
        DomainRepository domainRepository = new DomainRepositoryAdapter();
        Person maria = new Person(null, "Maria");

        // when
        Either<Error, Person> savedMaria = domainRepository.save(maria);

        // then
        assertThat(savedMaria.isRight()).isTrue();
        assertThat(savedMaria.get().getId()).isNotBlank();
        assertThat(savedMaria.get().getName()).isEqualTo("Maria");
    }

    @Test
    void shouldSaveAndFindPerson() {
        // given
        DomainRepository domainRepository = new DomainRepositoryAdapter();
        Person maria = new Person(null, "Maria");
        Either<Error, Person> savedMaria = domainRepository.save(maria);

        // when
        Either<Error, Person> fetchedMaria = domainRepository.findById(savedMaria.get().getId());

        // then
        assertThat(fetchedMaria.isRight()).isTrue();
        assertThat(fetchedMaria.get().getId()).isNotBlank();
        assertThat(fetchedMaria.get().getName()).isEqualTo("Maria");
    }

    @Test
    void shouldMapDatabaseError() {
        DomainRepository domainRepository = new DomainRepositoryAdapter();
        Either<Error, Person> fetchedMaria = domainRepository.findById("Internal issue");

        // then
        assertThat(fetchedMaria.isLeft()).isTrue();
        assertThat(((Error.DatabaseError) fetchedMaria.getLeft()).throwable().getMessage()).isEqualTo("Internal issue");
    }

    @Test
    void shouldMapMissingPersonScenario() {
        DomainRepository domainRepository = new DomainRepositoryAdapter();
        Either<Error, Person> fetchedMaria = domainRepository.findById("XXX");

        // then
        assertThat(fetchedMaria.isLeft()).isTrue();
        assertThat(fetchedMaria.getLeft()).isEqualTo(new Error.PersonNotFound("XXX"));
    }

    @Test
    void shouldCombineOperations() {
        // given
        DomainRepository domainRepository = new DomainRepositoryAdapter();
        Person maria = new Person(null, "Maria");

        // when
        Either<Error, String> combinedResult = domainRepository.save(maria)
                .flatMap(savedMaria -> domainRepository.findById(savedMaria.getId()))
                .map(Person::getName)
                .map(String::toUpperCase);

        // then
        assertThat(combinedResult.isRight()).isTrue();
        assertThat(combinedResult.get()).isEqualTo("MARIA");
    }

    static class DomainRepositoryAdapter implements DomainRepository {
        private final NativeRepo nativeRepo = new NativeRepo();


        @Override
        public Either<Error, Person> findById(String id) {
            return Try.of(() -> nativeRepo.findById(id))
                    .map(Optional::get).toEither()
                    .mapLeft(throwable ->
                            switch (throwable) {
                                case NoSuchElementException ex -> new Error.PersonNotFound(id);
                                default -> new Error.DatabaseError(throwable);
                            });
        }

        @Override
        public Either<Error, Person> save(Person person) {
            return Try.of(() -> nativeRepo.save(person)).toEither()
                    .mapLeft(Error.DatabaseError::new);
        }

        static class NativeRepo {

            private final Map<String, String> storage = new ConcurrentHashMap<>();

            Optional<Person> findById(String id) {
                if (id.equals("Internal issue")) {
                    throw new RuntimeException("Internal issue");
                }

                if (storage.containsKey(id)) {
                    return Optional.of(
                            Person.builder()
                                    .id(id)
                                    .name(storage.get(id))
                                    .build());
                } else {
                    return Optional.empty();
                }
            }

            Person save(Person person) {
                Person readyPerson = Person.builder()
                        .id(isNull(person.getId()) ? UUID.randomUUID().toString() : person.getId())
                        .name(person.getName())
                        .build();

                storage.put(readyPerson.getId(), readyPerson.getName());
                return readyPerson;
            }
        }
    }

    ;

    interface DomainRepository {
        Either<Error, Person> findById(String id);

        Either<Error, Person> save(Person person);
    }

    @Value
    @Builder
    static class Person {
        String id;
        String name;
    }

    sealed interface Error permits Error.DatabaseError, Error.PersonNotFound {
        record DatabaseError(Throwable throwable) implements Error {
        }

        record PersonNotFound(String id) implements Error {
        }
    }
}
