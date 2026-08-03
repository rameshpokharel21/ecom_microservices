package com.ramesh.order.clients;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

//Shared outbound-HTTP plumbing for order-service. Every RestClient in this
//service is built from one of the two builders below; the per-service client
//configs (ProductServiceClientConfig, UserServiceClientConfig) only consume
//them. They live here rather than in one of those configs so that neither
//client owns infrastructure the other depends on.
//
//Both builders are prototype-scoped, matching Boot's own
//RestClientAutoConfiguration. RestClient.Builder is mutable (baseUrl() sets a
//field and returns this), so a singleton would be shared by every injection
//point and the two client configs would overwrite each other's base URL. That
//happened to work only because each sets and immediately build()s during
//single-threaded startup; any lazy or concurrent use would have sent requests
//to the wrong service. A prototype hands each consumer its own copy. Load
//balancing is unaffected: the post-processor runs on every instance created
//and reads the bean definition, not the instance.
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    //Applies Boot's own auto-configuration to a bare RestClient.builder():
    //request factory, HTTP message converters, and every RestClientCustomizer
    //bean. Micrometer registers tracing as a customizer, so skipping this would
    //silently drop trace propagation and break order -> product/user spans in
    //Zipkin. Both builders below go through it to share that baseline.
    private final RestClientBuilderConfigurer restClientBuilderConfigurer;

    //The ordinary builder, for absolute URLs.
    //
    //It has to exist. Boot's own RestClient.Builder bean is @ConditionalOnMissingBean,
    //so declaring any builder of our own makes it back off; without this one the
    //load-balanced builder below would become the only builder in the context and
    //every unqualified injection would get it, including Spring Cloud Netflix's
    //internal transport, which then cannot register with eureka-server because it
    //would be resolving "eureka-server" through a registry it is not yet in.
    //
    //@Primary because two beans of the same type make every unqualified injection
    //point ambiguous (NoUniqueBeanDefinitionException at startup); this is the
    //default winner, and @LoadBalanced is how a caller opts out of it.
    @Bean
    @Primary
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        return restClientBuilderConfigurer.configure(RestClient.builder());
    }

    //The service-discovery builder, for logical service IDs such as
    //"http://product-service", with no host and no port. @LoadBalanced is a marker that
    //LoadBalancerRestClientBuilderBeanPostProcessor looks for by bean name; it
    //mutates this builder in place, adding the interceptor that asks Eureka for
    //live instances and rewrites the URI to a real host:port before the call goes
    //out. That mutation is why this cannot be the same bean as the one above.
    //
    //@LoadBalanced is meta-annotated @Qualifier, so the same annotation on an
    //injection parameter selects this bean instead of the @Primary one.
    @Bean
    @LoadBalanced
    @Scope("prototype")
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return restClientBuilderConfigurer.configure(RestClient.builder());
    }
}
