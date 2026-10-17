package com.fromyourdream.backend.service;

import java.util.List;

public interface RedisService {

    List<String> findUserKeys();

    String createUserCode();
}
