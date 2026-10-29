package io.github.marishasahay.shortener.click;

import io.github.marishasahay.shortener.link.LinkApiController;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Click statistics of a link. */
@RestController
public class LinkStatsController {

  private final LinkStatsService statsService;

  public LinkStatsController(LinkStatsService statsService) {
    this.statsService = statsService;
  }

  /**
   * Returns click statistics.
   *
   * @param code the short code
   * @param days number of UTC days in the daily breakdown, including today
   * @return the statistics
   */
  @GetMapping(path = LinkApiController.BASE_PATH + "/{code}/stats",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public LinkStats stats(@PathVariable String code,
      @RequestParam(defaultValue = "7") int days) {
    return statsService.stats(code, days);
  }
}
