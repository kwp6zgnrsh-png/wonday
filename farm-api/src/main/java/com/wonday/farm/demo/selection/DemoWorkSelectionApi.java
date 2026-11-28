package com.wonday.farm.demo.selection;

import com.wonday.farm.auth.FarmPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Profile("farm-demo")
@RestController
public class DemoWorkSelectionApi {
    private final DemoWorkSelectionFixtureService service;
    public DemoWorkSelectionApi(DemoWorkSelectionFixtureService service){this.service=service;}
    @GetMapping(value="/demo/work-selection",produces="text/html;charset=UTF-8")
    public ResponseEntity<Resource> page(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ClassPathResource("demo/farm-work-selection.html"));
    }
    @GetMapping(value="/demo/api/work-selection/fixtures",produces="application/json;charset=UTF-8")
    public ResponseEntity<DemoWorkSelectionFixtureService.FixtureResponse> list(@AuthenticationPrincipal FarmPrincipal actor){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(id(actor)));
    }
    @PostMapping(value="/demo/api/work-selection/fixtures",produces="application/json;charset=UTF-8")
    public ResponseEntity<DemoWorkSelectionFixtureService.FixtureResponse> prepare(@AuthenticationPrincipal FarmPrincipal actor){
        var body=service.prepare(id(actor));
        return ResponseEntity.status(body.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore()).body(body);
    }
    private static long id(FarmPrincipal actor){
        if(actor==null)throw new AccessDeniedException("Farm session required");
        return actor.memberId();
    }
}
