package org.gslab;

import java.lang.reflect.Field;
import org.gslab.configuration.ServiceConfiguration;

public class HiBAService {

    private String name;
    private Executor executor;
    private Distributor distributor;
    private Taker taker;
    private ServiceConfiguration config;

    private HiBAService() {}

    public HiBAService(String name, Distributor distributor, Executor executor, Taker taker) {}

    public HiBAService(
            String name, Distributor distributor, Executor executor, Taker taker, ServiceConfiguration config) {}

    public static class builder {

        private final HiBAService service = new HiBAService();

        public builder Name(String name) {
            service.name = name;
            return this;
        }

        public builder Taker(Taker taker) {
            service.taker = taker;
            return this;
        }

        public builder Distributor(Distributor distributor) {
            service.distributor = distributor;
            return this;
        }

        public builder Executor(Executor executor) {
            service.executor = executor;
            return this;
        }

        public builder ServiceConfiguration(ServiceConfiguration config) {
            service.config = config;
            return this;
        }

        public HiBAService build() throws IllegalArgumentException {
            for (Field field : service.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    if (field.get(service) == null) {
                        throw new IllegalArgumentException(field.getName() + " can not be null");
                    }
                } catch (IllegalAccessException e) {
                    // shouldn't happen because I used setAccessible
                }
            }
            return service;
        }
    }

    public HiBAService getNewInstance() {
        return new builder()
                .Name(name)
                        .Distributor(distributor.getNewInstance())
                        .Executor(executor.getNewInstance())
                        .Taker(taker.getNewInstance())
                        .ServiceConfiguration(config)
                        .build();
    }

    public String getName() {
        return name;
    }

    public Distributor getDistributor() {
        return distributor;
    }

    public Executor getExecutor() {
        return executor;
    }

    public Taker getTaker() {
        return taker;
    }

    public ServiceConfiguration getConfig() {
        return config;
    }
}
