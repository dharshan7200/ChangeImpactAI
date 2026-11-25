package com.changeimpact.dashboard;

import com.changeimpact.auth.security.UserPrincipal;
import com.changeimpact.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Team Dashboard", description = "Aggregated change risk, pull requests, quality and security across repositories")
public class TeamDashboardController {

    private final TeamDashboardService dashboardService;

    @GetMapping
    @Operation(summary = "Team dashboard for the last N days (1-90, default 30)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> dashboard(@AuthenticationPrincipal UserPrincipal principal,
                                                                      @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.build(principal.getId(), Math.max(1, Math.min(days, 90)))));
    }
}
