package com.paytm.booktickets;

import org.springframework.boot.SpringApplication;

public class TestBookticketsApplication {

	public static void main(String[] args) {
		SpringApplication.from(BookticketsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
