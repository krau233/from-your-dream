package com.fromyourdream.backend.controller;

import java.util.List;

import com.fromyourdream.backend.common.Result;
import com.fromyourdream.backend.service.GameService;
import com.fromyourdream.backend.service.GameService.GameView;
import com.fromyourdream.backend.service.GameService.Turn;
import com.fromyourdream.backend.service.GameService.TurnRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public Result<GameView> get(@RequestHeader("X-User-Id") String userId) {
        return Result.success(games.get(userId));
    }

    @GetMapping("/history")
    public Result<List<Turn>> history(@RequestHeader("X-User-Id") String userId) {
        return Result.success(games.history(userId));
    }

    @PostMapping("/turns")
    public Result<GameView> turn(@RequestHeader("X-User-Id") String userId, @RequestBody TurnRequest request) {
        return Result.success(games.turn(userId, request));
    }

    @PostMapping("/reset")
    public Result<GameView> reset(@RequestHeader("X-User-Id") String userId, @RequestBody ResetRequest request) {
        return Result.success(games.reset(userId, request.gameId()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> failure(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Result.error(exception.getStatusCode().value(), exception.getReason()));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Result.error(400, "请提供账号 ID 和正确的 JSON 请求内容。"));
    }

    public record ResetRequest(String gameId) {}
}
