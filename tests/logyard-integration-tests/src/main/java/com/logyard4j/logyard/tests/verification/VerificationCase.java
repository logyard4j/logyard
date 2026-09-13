package com.logyard4j.logyard.tests.verification;

interface VerificationCase {
    String description();

    void verify() throws Exception;
}
