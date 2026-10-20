package com.autofik.sos;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Boots the real context against whatever the .env points at, so it is not part of the plain
// verify: run it with -Pintegration where a database is reachable.
@Tag("integration")
@SpringBootTest
class SosApplicationTests {

  @Test
  void contextLoads() {}
}
