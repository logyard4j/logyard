package com.zsumz.logyard.tests.verification;

interface VerificationCase {
    String description();

    void verify() throws Exception;
}
