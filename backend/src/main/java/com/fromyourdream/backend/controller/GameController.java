package com.fromyourdream.backend.controller;

import java.util.List;

import com.fromyourdream.backend.common.Result;
import com.fromyourdream.backend.service.GameService;
import com.fromyourdream.backend.service.GameService.GameView;
import com.fromyourdream.backend.service.GameService.Turn;
import com.fromyourdream.backend.service.GameService.TurnRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameService games;

    public GameController(GameService games) {
        this.games = games;
    }

    @GetMapping
    public Result<GameView> get(@RequestParam(value = "userId", required = false) String userId,
                              @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        return Result.success(games.get(account(userId, headerUserId)));
    }

    @GetMapping("/history")
    public Result<List<Turn>> history(@RequestParam(value = "userId", required = false) String userId,
                                     @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        return Result.success(games.history(account(userId, headerUserId)));
    }

    @PostMapping("/turns")
    public Result<GameView> turn(@RequestParam(value = "userId", required = false) String userId,
                                @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
                                @RequestBody TurnRequest request) {
        return Result.success(games.turn(account(userId, headerUserId), request));
    }

    @PostMapping("/reset")
    public Result<GameView> reset(@RequestParam(value = "userId", required = false) String userId,
                                 @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
                                 @RequestBody ResetRequest request) {
        return Result.success(games.reset(account(userId, headerUserId), request.gameId()));
    }

    private String account(String userId, String headerUserId) {
        if (userId != null && headerUserId != null && !userId.equals(headerUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求中的账号 ID 不一致，请刷新页面后重试。");
        }
        String code = userId != null ? userId : headerUserId;
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未收到账号 ID，请重新选择身份后进入故事。");
        }
        return code;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> failure(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Result.error(exception.getStatusCode().value(), exception.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> invalidRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.error(400, "请求内容格式错误，请刷新页面后重试。"));
    }

    public record ResetRequest(String gameId) {}
}
