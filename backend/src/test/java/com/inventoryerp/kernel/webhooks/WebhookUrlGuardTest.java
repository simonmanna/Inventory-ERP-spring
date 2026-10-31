package com.inventoryerp.kernel.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class WebhookUrlGuardTest {

    final WebhookUrlGuard guard = new WebhookUrlGuard(false);

    @Test
    void refusesLocalAndPrivateTargets() throws Exception {
        for (String url : new String[] {"http://127.0.0.1/x", "http://localhost:8080/x", "http://10.1.2.3/x",
                "http://192.168.0.5/x", "http://172.16.9.9/x", "http://169.254.169.254/latest/meta-data",
                "http://100.64.0.1/x", "http://0.0.0.0/x", "http://[::1]/x", "http://[fd00::1]/x",
                "http://[::ffff:10.0.0.1]/x"}) {
            assertThat(guard.problem(url)).as(url).contains("public address");
        }
        assertThat(WebhookUrlGuard.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(WebhookUrlGuard.isPublic(InetAddress.getByName("2001:4860:4860::8888"))).isTrue();
    }

    @Test
    void refusesOtherSchemesAndMissingHosts() {
        assertThat(guard.problem("ftp://8.8.8.8/x")).isEqualTo("Webhook URL must use http or https.");
        assertThat(guard.problem("file:///etc/passwd")).isEqualTo("Webhook URL must use http or https.");
        assertThat(guard.problem("http:///x")).isEqualTo("Webhook URL has no host.");
        assertThatThrownBy(() -> guard.check("http://127.0.0.1/x")).isInstanceOf(ApiException.class);
        assertThat(guard.problem("https://8.8.8.8/hook")).isNull();
    }

    @Test
    void developmentSwitchAllowsPrivateTargets() {
        assertThat(new WebhookUrlGuard(true).problem("http://127.0.0.1:9000/hook")).isNull();
        assertThat(new WebhookUrlGuard(true).problem("ftp://127.0.0.1/hook")).isNotNull();
    }
}
