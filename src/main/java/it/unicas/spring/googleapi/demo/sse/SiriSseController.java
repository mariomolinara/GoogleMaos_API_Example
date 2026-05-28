package it.unicas.spring.googleapi.demo.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Controller SSE: espone l'endpoint a cui il server CASSITRACK
 * si connette per ricevere aggiornamenti SIRI 2.0 in streaming.
 *
 * UTILIZZO (Server-Sent Events):
 *
 *   Il server CASSITRACK (o qualsiasi client HTTP/EventSource) si connette a:
 *     GET http://localhost:8080/api/siri/stream
 *     Accept: text/event-stream
 *
 *   In JavaScript (browser o Node.js):
 *     const es = new EventSource('http://localhost:8080/api/siri/stream');
 *     es.addEventListener('siri-vehicle-monitoring', (e) => {
 *       const siriXml = e.data;
 *       // parse e elabora il messaggio SIRI 2.0
 *     });
 *
 *   In Java (altro server Spring Boot):
 *     WebClient.create().get()
 *       .uri("http://localhost:8080/api/siri/stream")
 *       .accept(MediaType.TEXT_EVENT_STREAM)
 *       .retrieve()
 *       .bodyToFlux(String.class)
 *       .subscribe(siriXml -> processSiri(siriXml));
 *
 * Gli aggiornamenti vengono inviati ogni 60 secondi (configurabile).
 * Un aggiornamento immediato viene inviato alla connessione del client.
 */
@RestController
@RequestMapping("/api/siri")
@CrossOrigin(origins = "*")   // permette connessioni da qualsiasi origine (inclusa la dashboard)
public class SiriSseController {

    private final SiriPushService siriPushService;

    public SiriSseController(SiriPushService siriPushService) {
        this.siriPushService = siriPushService;
    }

    /**
     * Endpoint SSE principale.
     * Il server CassiTrack si connette qui per ricevere
     * VehicleMonitoring SIRI 2.0 ogni 60 secondi.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSiri() {
        return siriPushService.registerEmitter();
    }
}

