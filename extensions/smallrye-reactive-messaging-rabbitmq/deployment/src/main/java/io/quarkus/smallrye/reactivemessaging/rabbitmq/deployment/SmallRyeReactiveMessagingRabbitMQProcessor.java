package io.quarkus.smallrye.reactivemessaging.rabbitmq.deployment;

import jakarta.enterprise.context.ApplicationScoped;

import com.rabbitmq.client.impl.CredentialsProvider;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem.ExtendedBeanConfigurator;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.smallrye.reactivemessaging.rabbitmq.runtime.CredentialsProviderLink;
import io.quarkus.smallrye.reactivemessaging.rabbitmq.runtime.RabbitMQRecorder;
import io.quarkus.smallrye.reactivemessaging.rabbitmq.runtime.RabbitmqClientConfigCustomizer;
import io.smallrye.common.annotation.Identifier;

public class SmallRyeReactiveMessagingRabbitMQProcessor {

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(Feature.MESSAGING_RABBITMQ);
    }

    @BuildStep
    AdditionalBeanBuildItem build() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(RabbitmqClientConfigCustomizer.class)
                .setUnremovable()
                .build();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public void dynamicCredentials(RabbitMQRecorder recorder,
            RabbitMQBuildTimeConfig rabbitMQBuildTimeConfig,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> configDefaults) {

        if (rabbitMQBuildTimeConfig.credentialsProvider().isPresent()) {
            String credentialsProvider = rabbitMQBuildTimeConfig.credentialsProvider().get();

            RuntimeValue<CredentialsProviderLink> credentialsProviderLink = recorder.configureOptions(
                    credentialsProvider,
                    rabbitMQBuildTimeConfig.credentialsProviderName());

            String identifier = "credentials-provider-link-" + credentialsProvider;

            ExtendedBeanConfigurator rabbitMQOptionsConfigurator = SyntheticBeanBuildItem
                    .configure(CredentialsProviderLink.class)
                    .defaultBean()
                    .addType(CredentialsProvider.class)
                    .addQualifier().annotation(Identifier.class).addValue("value", identifier).done()
                    .scope(ApplicationScoped.class)
                    .runtimeValue(credentialsProviderLink)
                    .unremovable()
                    .setRuntimeInit();

            configDefaults.produce(new RunTimeConfigurationDefaultBuildItem("rabbitmq-credentials-provider-name", identifier));
            syntheticBeans.produce(rabbitMQOptionsConfigurator.done());
        }
    }

}
