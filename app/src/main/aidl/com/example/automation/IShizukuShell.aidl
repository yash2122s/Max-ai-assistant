package com.example.automation;

interface IShizukuShell {
    String runCommand(String command);
    byte[] runCommandBytes(String command);
    void destroy();
}
