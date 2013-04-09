package edu.uccs.cs.cs5050.spotmarket;

import org.apache.log4j.Logger;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class ContextLoader {
    private static final Logger logger = Logger.getLogger(ContextLoader.class);

    public static void main(String[] args) {
        logger.info("Loading Spring application context.");
        new ClassPathXmlApplicationContext("spring-config.xml");
    }
}
