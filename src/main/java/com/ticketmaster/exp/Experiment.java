package com.ticketmaster.exp;

import com.ticketmaster.exp.publish.MeasurerPublisher;
import com.ticketmaster.exp.publish.PrintStreamMeasurer;
import com.ticketmaster.exp.util.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ticketmaster.exp.TrialType.CANDIDATE;
import static com.ticketmaster.exp.TrialType.CONTROL;
import static com.ticketmaster.exp.util.Selectors.never;
import static com.ticketmaster.exp.util.Selectors.always;

/**
 * Created by dannwebster on 10/12/14.
 */
public class Experiment<I, O, M> implements Function<I, O> {

    private final String name;
    private final Duple<Function<I, TrialResult<O>>> controlThenCandidate;
    private final Function<Result<O>, Try<O>> returnChoice;
    private final BooleanSupplier doExperimentWhen;
    private final BooleanSupplier doSeriallyWhen;
    private final Clock clock;

    private final BiFunction<M, M, Boolean> sameWhen;
    private final BiFunction<Exception, Exception, Boolean> exceptionsSameWhen;
    private final Function<O, M> simplifier;

    private final Publisher<O> publisher;

    public interface Builder<I, O, M, E extends Experiment<I, O, M>, B extends Builder<I, O, M, E, B>> extends Supplier<Experiment<I, O, M>>{
        public Experiment<I, O, M> get();
        public B simplifiedBy(Function<O, M> simplifier);
        public B sameWhen(BiFunction<M, M, Boolean> sameWhen);
        public B exceptionsSameWhen(BiFunction<Exception, Exception, Boolean> sameWhen);
        public B timedBy(Clock clock);
        public B publishedBy(Publisher<O> publisher);
        public B returnChoice(Function<Result<O>, Try<O>> returnChoice);
        public B doExperimentWhen(BooleanSupplier doExperimentWhen);
        public B doSeriallyWhen(BooleanSupplier doSeriallyWhen);
        public B control(Function<I, O> control);
        public B candidate(Function<I, O> candidate);
    }

    public static class Simple<I, O> extends Experiment<I, O, O> {
        public Simple(String name,
                      Function<I, O> control,
                      Function<I, O> candidate,
                      Function<Result<O>, Try<O>> returnChoice,
                      BooleanSupplier doExperimentWhen,
                      BooleanSupplier doSeriallyWhen,
                      Function<O, O> simplifier,
                      BiFunction<O, O, Boolean> sameWhen,
                      BiFunction<Exception, Exception, Boolean> exceptionsSameWhen,
                      Publisher<O> publisher,
                      Clock clock) {
            super(name, control, candidate, returnChoice,
                    doExperimentWhen, doSeriallyWhen, simplifier, sameWhen, exceptionsSameWhen,
                    publisher, clock);
        }
    }

    public static abstract class BaseBuilder<I, O, M, E extends Experiment<I, O, M>, B extends Builder<I, O, M, E, B>>
            implements Builder<I, O, M, E , B> {
        String name;
        Function<I, O> control;
        Function<I, O> candidate;
        Function<Result<O>, Try<O>> returnChoice = ReturnChoices.alwaysControl();
        BooleanSupplier doExperimentWhen = always();
        BooleanSupplier doSeriallyWhen = never();

        Function<O, M> simplifier;
        BiFunction<M, M, Boolean> sameWhen = Objects::equals;
        BiFunction<Exception, Exception, Boolean> exceptionsSameWhen = SameWhens.classesMatch();

        Publisher<O> publisher = MeasurerPublisher.DEFAULT;

        Clock clock = Clock.systemUTC();

        BaseBuilder(String name) {
            this.name = name;
        }

        public abstract E get();

        private B me() {
            return (B) this;
        }

        public B simplifiedBy(Function<O, M> simplifier) {
            this.simplifier = simplifier;
            return me();
        }

        public B sameWhen(BiFunction<M, M, Boolean> sameWhen) {
            this.sameWhen = sameWhen;
            return me();
        }

        public B exceptionsSameWhen(BiFunction<Exception, Exception, Boolean> sameWhen) {
            this.exceptionsSameWhen = exceptionsSameWhen;
            return me();
        }

        public B timedBy(Clock clock) {
            this.clock = clock;
            return me();
        }

        public B publishedBy(Publisher<O> publisher) {
            this.publisher = publisher;
            return me();
        }

        public B returnChoice(Function<Result<O>, Try<O>> returnChoice) {
            this.returnChoice = returnChoice;
            return me();
        }

        public B doExperimentWhen(BooleanSupplier doExperimentWhen) {
            this.doExperimentWhen = doExperimentWhen;
            return me();
        }

        public B control(Function<I, O> control) {
            this.control = control;
            return me();
        }

        public B candidate(Function<I, O> candidate) {
            this.candidate = candidate;
            return me();
        }

        public B doSeriallyWhen(BooleanSupplier doSeriallyWhen) {
            this.doSeriallyWhen = doSeriallyWhen;
            return me();
        }

    }

    public static class SimpleBuilder<I, O> extends BaseBuilder<I, O, O, Simple<I, O>, SimpleBuilder<I, O>> {
        public SimpleBuilder(String name) {
            super(name);
            super.simplifiedBy(a -> a);
        }

        @Override
        public Simple<I, O> get() {
            return new Simple<>(name, control, candidate,
                    returnChoice, doExperimentWhen, doSeriallyWhen,
                    simplifier, sameWhen, exceptionsSameWhen,
                    publisher, clock);
        }
    }
    public static class ExperimentBuilder<I, O, M> extends BaseBuilder<I, O, M, Experiment<I, O, M>, ExperimentBuilder<I, O, M>> {

        public ExperimentBuilder(String name) {
            super(name);
        }

        public Experiment<I, O, M> get() {
            return new Experiment<>(name, control, candidate,
                    returnChoice, doExperimentWhen, doSeriallyWhen, simplifier,
                    sameWhen, exceptionsSameWhen, publisher, clock);
        }
    }

    public static <I, O> SimpleBuilder<I, O> simple(String name) {
        return new SimpleBuilder<>(name);
    }
    public static <I, O, M> ExperimentBuilder<I, O, M> named(String name) {
        return new ExperimentBuilder<>(name);
    }

    Experiment(
            String name, 
            Function<I, O> control,
            Function<I, O> candidate,
            Function<Result<O>, Try<O>> returnChoice,
            BooleanSupplier doExperimentWhen,
            BooleanSupplier doSeriallyWhen,
            Function<O, M> simplifier,
            BiFunction<M, M, Boolean> sameWhen,
            BiFunction<Exception, Exception, Boolean> exceptionsSameWhen,
            Publisher<O> publisher,
            Clock clock) {

        Assert.hasText(name, "name must be non-null and have text");
        Assert.notNull(control, "control must be non-null");
        Assert.notNull(candidate, "candidate must be non-null");
        Assert.notNull(returnChoice, "returnChoice must be non-null");
        Assert.notNull(doExperimentWhen, "doExperimentWhen must be non-null");
        Assert.notNull(sameWhen, "sameWhen must be non-null");
        Assert.notNull(exceptionsSameWhen, "exceptionsSameWhen must be non-null");
        Assert.notNull(doSeriallyWhen, "doSeriallyWhen must be non-null");
        Assert.notNull(simplifier, "simplifier must be non-null");
        Assert.notNull(publisher, "publisher must be non-null");
        Assert.notNull(clock, "clock must be non-null");

        this.name = name;
        this.controlThenCandidate = Duple.from(
                (args) -> observe(CONTROL, control, args),
                (args) -> observe(CANDIDATE, candidate, args)
        );
        this.returnChoice = returnChoice;
        this.doExperimentWhen = doExperimentWhen;
        this.sameWhen = sameWhen;
        this.exceptionsSameWhen = exceptionsSameWhen;
        this.doSeriallyWhen = doSeriallyWhen;
        this.simplifier = simplifier;
        this.publisher = publisher;
        this.clock = clock;
    }


    public final O apply(I args) {
        return perform(args).getOrThrowUnchecked();
    }

    public final Try<O> perform(I args) {
        return returnChoice.apply(getResult(args));
    }

    public final Result<O> getResult(I args) {
        Result<O> result;
        Instant timestamp = Instant.now();
        if (doExperimentWhen.getAsBoolean()) {
            Stream<Function<I, TrialResult<O>>> stream = (doSeriallyWhen.getAsBoolean()) ?
                    controlThenCandidate.stream() :
                    controlThenCandidate.parallelStream();
            Map<TrialType, List<TrialResult<O>>> results = stream
                    .map((function) -> function.apply(args))
                    .collect(Collectors.groupingBy((TrialResult t) -> t.getTrialType()));

            result = new Result<>(
                    name,
                    timestamp,
                    results.get(CANDIDATE).get(0),
                    results.get(CONTROL).get(0));

            MatchType matchType = determineMatch(result);
            publisher.publish(matchType, result);
        } else {
            TrialResult<O> trialResult = controlThenCandidate.getE1().apply(args);
             result = new Result<>(
                    name,
                    timestamp,
                    trialResult,
                    trialResult);
        }
        return result;
    }

    public MatchType determineMatch(Result<O> result) {
        return result.determineMatch(this.simplifier, this.sameWhen, this.exceptionsSameWhen);
    }

    final TrialResult<O> observe(TrialType trialType, Function<I, O> callable, I args) {
        Instant start = clock.instant();
        Exception exception = null;
        O value = null;
        try {
            value = callable.apply(args);
        } catch (Exception t) {
            exception = t;
        }
        Instant end = clock.instant();
        Duration duration = Duration.between(start, end);
        return new TrialResult<>(trialType, duration, exception, value);
    }
}
