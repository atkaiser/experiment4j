experiment4j
============

Summary
-------
A Java port of Github's [Science framework](https://github.com/github/dat-science).

Motivation
----------
I read ["Move Fast, Break Nothing"](http://zachholman.com/talk/move-fast-break-nothing/) by Zack Holman, and I 
really wanted the capabilities of Github's [Science framework](https://github.com/github/dat-science) in Java 8.

I work with a large production system where we are actively trying to replace a bunch of legacy code with
newer implementations. Our core goals are to: 
* Improve performance
* Maintain backwards compatibility

With those objectives, Science is exactly the thing we need. The ability to allow developers to easily and consistently express the capability to quickly perform these kinds of experiments with a DSL is vital.

Methodology
-----------
I based this implementation on reading the Science documentation, and building out the capabilities from the
functionality expressed there. In other words, I did not focus on porting the implementation. No doubt, greater insight could be gained from spending more time on grokking the Ruby implementation, but it was faster for me to work directly from the docs, and build what I needed from my own understanding of the pattern.

Domain Concepts
---------------
There are only a few main domain concepts for this framework:

* _Experiment_: a wrapper around 2 implementations of the same business logic: the _Control_ and the _Candidate_. The wrapper   
  1. Runs the _Control_ and _Candidate_ implementations in parallel (using a thread executor)
  2. Times the execution of both the _Control_ and _Candidate_
  3. Compares the results for equality
  3. Pushes the comparison data (generated in 2 and 3 above) to a _Publisher_
  4. Returns the response of the _Control_ to the calling code
* _Science_: A Cache for Experiments.
* _Control_: The default version of business logic to be run during an _Experiemnt_. The response from the _Control_ will always be returned by an _Experiment_
* _Candidate_: The experimental version of business logic to be run in an _Experiment_. The response from the _Candidate_ will never be returned by an _Experiment_
* _Publisher_: An interface for outputting the data generated by an _Experiment_, such as _Control_ and _Candidate_ response times, and whether the output of the two functions match. Usually, this will be an adapter to a Metrics framework, such as [CodaHale Metrics](http://mvnrepository.com/artifact/com.codahale.metrics).

DSL Syntax
----------
Some examples of the DSL syntax are found here: [ExperimentTest.java](https://github.com/dannwebster/experiment4j/blob/master/src/test/java/com/ticketmaster/exp/ExperimentTest.java)

Here is a contrived example Experiment that has been annotated expose all of the bells and whistles:

Person.java

```java
    public class Person {
        private final String firstName;
        private final String lastName;

        public Person(Stirng fname, String lname) {
            this.firstName = fname;
            this.lastName = lname;
        }

        public String getFirstName() { return firstName; }
        public String getLastName() { return firstName; }
    }
```

ExperimentExample.java

```java

    // The type Parameters for an Experiment are
    // #1) <I> The input type of the candidate and control functions
    // #2) <O> The ouptut type of the candidate and control functions
    // #3) <M> The intermediate type generated by the "simplifiedBy" function, 
    //         which is used to calculate matching
    Experiment<Person, String, Integer> experiment = 

        // This experiment is named "my experiment".
        // This is important, as the name will be passed to 
        // the publisher, which can help distinguish it from other experiments.
        // The named() method generates an ExperimentBuilder, which is of type 
        // Supplier<Experiment>
        Experiment.named("my experiment")

        // The "control" method will always be performed 
        // The input type is the first type parameter (eg, Person)
        // The output type is the second type parameter (eg, String)
        .control( (Person p) ->  p.getFirstName() + " " + p.getLastName() ) 

        // The "candidate" method will be performed when 
        // "doExperimentWhen" BooleanSupplier returns true
        .candidate( (Person p) -> String.format("%s %s", p.getFirstName(), p,getLastName()) 

        // this Clock instance is used to generate the duration of the 
        // candidate and control executions.
        // It defaults to Clock.systemUTC(), but can be overridden to make testing easier
        .timedBy(Clock.systemUTC())

        // This output of this function is the Integer value that will be 
        // compared by the "sameWhen" function to determine ifthe two responses are equal. 
        // The output type of this function is the 3rd type parameter (eg, Integer)
        .simplifiedBy( (String name) -> name.hashCode() ) 

        // the experiment will be performed (that is, both the control and the candidate will be run)
        // when the doExperimentWhen BooleanSupplier returns true.
        // Otherwise, only the control will be run
        // In this case, the experiment will always be run 
        // See Selectors for some pre-implemented BooleanSelectors, or code your own 
        .doExperimentWhen(Selectors.always()) 

        // the candidate and control be run serially when the doSeriallyWhen BooleanSupplier returns true;
        // otherwise, it will return run them in a parallel stream, such that they run simultaneously
        // In this case, they will never be run serially (always run in parallel)
        // See Selectors for some pre-implemented BooleanSelectors, or code your own 
        .doSeriallyWhen(Selectors.never()) 

        // the candidate and control are considered "equal" when Object.equals() 
        // returns "true" on the result of the simplifiedBy function
        .sameWhen(Objects::equals) 

        // By default, an Experiment returns the control result.
        // by setting the returnChoice function, you can determine
        // which result you want to use. 
        // See ReturnChoices for some pre-implemented Functions, or code your own 
        .returnChoice(ReturnChoices::alwaysCandidate)

        // if both the underlying Candidate and Control methods were to throw exceptions, 
        // this is how it would be determined if they were equal
        // See SameWhens for more choices
        .exceptionsSameWhen(SameWhens.classesMatch()) 

        // this will print the timing and the match status to System.out,
        .publishedBy(new SystemOutPublisher())) 


        // because an ExperimentBuilder is a Supplier<Experiment>, the 
        // Experiment object is generated by the get() method
        .get();

    // Science is a cache for experiments.
    // It takes a Supplier<Experiment> as its second argument.
    // Since a ExperimentBuilder is a Supplier<Experiment>, you can pass
    // an ExperimentBuilder as your 2nd argument
    Science.science().experiment("my experiment", () -> experiment);

    String presidentName = Science.science().doExperiment("my experiment", new Person("George", "Washington");
    assert presidentName.equals("George Washington");

    // Every Experiment is a Function<I, O> that mirrors the input and output 
    // type parameters of the candidate and control functions
    Function<Person, String> getName = experiment;

    String authorName = getName.apply(new Person("Raymond", "Carver"));

    assert authorName.equals("Raymond Carver");
```
