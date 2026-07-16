package com.neeraj.upi.common.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.util.Arrays;

/**
 * Aspect for logging method execution details (entry, exit, exceptions, execution time)
 * across REST Controllers, Service layers, and Kafka consumers/producers.
 */
@Aspect
@Slf4j
public class LoggingAspect {

    /**
     * Pointcut that matches all Spring REST controllers.
     */
    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void restControllerPointcut() {}

    /**
     * Pointcut that matches all Spring service components.
     */
    @Pointcut("within(@org.springframework.stereotype.Service *)")
    public void servicePointcut() {}

    /**
     * Pointcut that matches all Kafka listeners, publishers, and consumer/producer classes.
     */
    @Pointcut("within(com.neeraj.upi..kafka..*) || execution(* com.neeraj.upi..kafka..*(..))")
    public void kafkaPointcut() {}

    /**
     * Around advice that intercepts execution, measures time, and logs details.
     */
    @Around("restControllerPointcut() || servicePointcut() || kafkaPointcut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();

        String maskedArgs = maskSensitiveData(Arrays.toString(args));
        log.info("Entering: [{}.{}] with arguments: {}", className, methodName, maskedArgs);

        long startTime = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long elapsedTime = System.currentTimeMillis() - startTime;
            
            String maskedResult = maskSensitiveData(String.valueOf(result));
            log.info("Exiting: [{}.{}] | Return: {} | Duration: {} ms", className, methodName, maskedResult, elapsedTime);
            return result;
        } catch (Throwable throwable) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("Exception in: [{}.{}] after {} ms | Message: {}", className, methodName, elapsedTime, throwable.getMessage());
            throw throwable;
        }
    }

    /**
     * Utility method to mask sensitive data like PINs, passwords, and tokens.
     */
    private String maskSensitiveData(String input) {
        if (input == null) {
            return null;
        }
        // Match pin=xxxx, Pin=xxxx, PIN=xxxx and replace with ****
        String masked = input.replaceAll("(?i)(pin\\s*=\\s*)[^,\\}\\)]+", "$1****");
        // Match password=xxxx, Password=xxxx and replace with ****
        masked = masked.replaceAll("(?i)(password\\s*=\\s*)[^,\\}\\)]+", "$1****");
        // Match secret=xxxx, Secret=xxxx and replace with ****
        masked = masked.replaceAll("(?i)(secret\\s*=\\s*)[^,\\}\\)]+", "$1****");
        // Match token=xxxx, Token=xxxx and replace with ****
        masked = masked.replaceAll("(?i)(token\\s*=\\s*)[^,\\}\\)]+", "$1****");
        return masked;
    }
}
