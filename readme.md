Scalyr Java Client Library
---
[![CircleCI](https://circleci.com/gh/scalyr/Scalyr-Java-Client/tree/master.svg?style=svg)](https://circleci.com/gh/scalyr/Scalyr-Java-Client/tree/master)

This is the source code for the Java client to the Scalyr Logs services.
See [scalyr.com/logapijava](https://www.scalyr.com/help/java-api) for an introduction to the
API.

We do not actively solicit outside contributions to the client library, but if you'd
like to submit a patch, feel free to get in touch (contact@scalyr.com). And of course,
feedback, requests, and suggestions are always welcome!


### Adding to your project

##### With Maven

Add the following dependency to your project's pom.xml (check [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cscalyr%20scalyr-client) for the latest version):

        <dependency>
            <groupId>com.scalyr</groupId>
            <artifactId>scalyr-client</artifactId>
            <version>6.0.19</version>
        </dependency>


##### Downloading JARs directly

* Download the Java client library from [Maven Central](https://oss.sonatype.org/content/groups/public/com/scalyr/scalyr-client/6.0.19/scalyr-client-6.0.19.jar) and add it to your project.


#### Note about json-simple

The com.scalyr.api.json package contains a bastardized copy of the json-simple library
(http://code.google.com/p/json-simple/). We have removed code not needed for our
purposes, and renamed the package to avoid conflicts. Thanks to Yidong Fang and Chris
Nokleberg, the authors of json-simple, for this very handy library.
