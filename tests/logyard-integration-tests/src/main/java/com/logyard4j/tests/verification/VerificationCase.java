package com.logyard4j.tests.verification;

interface VerificationCase {
    String description();

    void verify() throws Exception;
}
