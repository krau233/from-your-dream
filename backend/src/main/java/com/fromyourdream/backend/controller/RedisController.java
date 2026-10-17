package com.fromyourdream.backend.controller;

import java.util.List;

import com.fromyourdream.backend.common.Result;
import com.fromyourdream.backend.service.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/redis")
public class RedisController {

    private static final Logger log =
            LoggerFactory.getLogger(RedisController.class);

    private final RedisService redisService;

    public RedisController(RedisService redisService) {
        this.redisService = redisService;
    }

    @PostMapping("/user-code")
    public ResponseEntity<Result<String>> createUserCode() {
        try {
            String code = redisService.createUserCode();
            return ResponseEntity.ok(Result.success("生成成功", code));
        } catch (DataAccessException | IllegalStateException exception) {
            log.error("生成用户字符串失败", exception);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.<String>error(503, "生成失败，请稍后重试"));
        }
    }

    @GetMapping("/user-keys")
    public ResponseEntity<Result<List<String>>> getUserKeys() {
        try {
            List<String> keys = redisService.findUserKeys();
            return ResponseEntity.ok(Result.success("查询成功", keys));
        } catch (DataAccessException exception) {
            log.error("查询 Redis user 前缀 key 失败", exception);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Result.<List<String>>error(503, "Redis 暂不可用"));
        }
    }
}