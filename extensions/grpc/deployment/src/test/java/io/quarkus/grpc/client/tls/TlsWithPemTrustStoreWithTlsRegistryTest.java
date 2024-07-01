package io.quarkus.grpc.client.tls;

import static io.restassured.RestAssured.get;
import static org.assertj.core.api.Assertions.assertThat;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import me.escoffier.certs.Format;
import me.escoffier.certs.junit5.Certificate;
import me.escoffier.certs.junit5.Certificates;

@Certificates(baseDir = "target/certs", certificates = {
        @Certificate(name = "grpc", password = "password", formats = { Format.JKS, Format.PEM, Format.PKCS12 }, client = true)
})
class TlsWithPemTrustStoreWithTlsRegistryTest {

    private static final String configuration = """
            quarkus.tls.trust-store.pem.certs=target/certs/grpc-client-ca.crt

            quarkus.grpc.clients.hello.host=localhost
            quarkus.grpc.clients.hello.port=9001
            quarkus.grpc.clients.hello.plain-text=false
            quarkus.grpc.clients.hello.use-quarkus-grpc-client=true

            # Legacy server
            quarkus.grpc.server.ssl.certificate=target/certs/grpc.crt
            quarkus.grpc.server.ssl.key=target/certs/grpc.key
            """;

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest().setArchiveProducer(
            () -> ShrinkWrap.create(JavaArchive.class)
                    .addPackage(HelloWorldTlsEndpoint.class.getPackage())
                    .addPackage(io.grpc.examples.helloworld.GreeterGrpc.class.getPackage())
                    .add(new StringAsset(configuration), "application.properties"));

    @Test
    void testClientTlsConfiguration() {
        String response = get("/hello/blocking/neo").asString();
        assertThat(response).isEqualTo("Hello neo");
    }
}
