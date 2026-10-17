package com.fromyourdream.backend.service.impl;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fromyourdream.backend.service.RedisService;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisServiceImpl implements RedisService {

    private static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    public RedisServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String createUserCode() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
            }
            String code = builder.toString();
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent("user_" + code, "reserved");

            if (Boolean.TRUE.equals(created)) {
                return code;
            }
            if (created == null) {
                throw new IllegalStateException("Redis 未返回占用结果");
            }
        }
        throw new IllegalStateException("生成用户字符串达到最大重试次数");
    }

    @Override
    public List<String> findUserKeys() {
        ScanOptions options = ScanOptions.scanOptions()
                .match("user*")
                .count(500)
                .build();

        Set<String> keys = new LinkedHashSet<>();

        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }

        return new ArrayList<>(keys);
    }
}