package com.changeimpact.admin;

import com.changeimpact.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Operational endpoints; ROLE_ADMIN only (method security). */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@SecurityRequirement(name = "BearerAuth")
@Tag(name = "Administration", description = "Operational tasks (ROLE_ADMIN)")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final RetentionService retentionService;

    @PostMapping("/retention/run")
    @Operation(summary = "Apply the data retention policy now")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runRetention() {
        return ResponseEntity.ok(ApiResponse.success(retentionService.run()));
    }
}
