package com.github.h3yow.privacyreleasegatewayjava.web;

import com.github.h3yow.privacyreleasegatewayjava.engine.GatewayService;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryRequest;
import com.github.h3yow.privacyreleasegatewayjava.model.QueryResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/release")
public class GatewayController {

    private final GatewayService service;

    public GatewayController(GatewayService service) {
        this.service = service;
    }

    @PostMapping
    public QueryResponse release(@RequestBody QueryRequest request) {
        return service.release(request);
    }
}
