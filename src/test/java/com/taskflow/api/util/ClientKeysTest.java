package com.taskflow.api.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las IPv4 cuentan tal cual y las IPv6 por su prefijo /64.
 */
class ClientKeysTest {

    @Test
    void ipv4TalCual() {
        assertThat(ClientKeys.ip("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6PorSuPrefijo64() {
        assertThat(ClientKeys.ip("2001:db8:1:2::1")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKeys.ip("2001:0db8:0001:0002:ffff:eeee:dddd:cccc")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKeys.ip("[2001:db8:1:2::9]")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKeys.ip("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
        assertThat(ClientKeys.ip("2001:db8:1:3::1")).isNotEqualTo(ClientKeys.ip("2001:db8:1:2::1"));
    }

    @Test
    void ipv4DentroDeIpv6CuentaComoIpv4() {
        assertThat(ClientKeys.ip("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void loQueNoEsUnaIpNoSeResuelve() {
        // Nunca una consulta DNS: un nombre se usa tal cual
        assertThat(ClientKeys.ip("localhost")).isEqualTo("localhost");
        assertThat(ClientKeys.ip("no:es:una:ip:zz")).isEqualTo("no:es:una:ip:zz");
        assertThat(ClientKeys.ip(null)).isEqualTo("desconocida");
    }
}
