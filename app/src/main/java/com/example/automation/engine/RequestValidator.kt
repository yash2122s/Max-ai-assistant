package com.example.automation.engine

interface RequestValidator {
    fun validate(request: ExecutionRequest): Boolean
}
