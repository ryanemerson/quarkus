package io.quarkus.vertx.web.deployment;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import javax.inject.Singleton;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem.BeanClassAnnotationExclusion;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.AnnotationStore;
import io.quarkus.arc.processor.AnnotationsTransformer;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.arc.processor.DotNames;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.AnnotationProxyBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.substrate.ReflectiveClassBuildItem;
import io.quarkus.deployment.util.HashUtil;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.HandlerType;
import io.quarkus.vertx.http.runtime.HttpConfiguration;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.RoutingExchange;
import io.quarkus.vertx.web.runtime.RoutingExchangeImpl;
import io.quarkus.vertx.web.runtime.VertxWebRecorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

class VertxWebProcessor {

    private static final Logger LOGGER = Logger.getLogger(VertxWebProcessor.class.getName());

    private static final DotName ROUTE = DotName.createSimple(Route.class.getName());
    private static final DotName ROUTES = DotName.createSimple(Route.Routes.class.getName());
    private static final DotName ROUTING_CONTEXT = DotName.createSimple(RoutingContext.class.getName());
    private static final DotName RX_ROUTING_CONTEXT = DotName
            .createSimple(io.vertx.reactivex.ext.web.RoutingContext.class.getName());
    private static final DotName ROUTING_EXCHANGE = DotName.createSimple(RoutingExchange.class.getName());
    private static final String HANDLER_SUFFIX = "_RouteHandler";

    HttpConfiguration httpConfiguration;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FeatureBuildItem.VERTX_WEB);
    }

    @BuildStep
    void unremovableBeans(BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {
        unremovableBeans.produce(new UnremovableBeanBuildItem(new BeanClassAnnotationExclusion(ROUTE)));
        unremovableBeans.produce(new UnremovableBeanBuildItem(new BeanClassAnnotationExclusion(ROUTES)));
    }

    @BuildStep
    void validateBeanDeployment(
            ValidationPhaseBuildItem validationPhase,
            BuildProducer<AnnotatedRouteHandlerBuildItem> routeHandlerBusinessMethods,
            BuildProducer<ValidationErrorBuildItem> errors) {

        // We need to collect all business methods annotated with @Route first
        AnnotationStore annotationStore = validationPhase.getContext().get(BuildExtension.Key.ANNOTATION_STORE);
        for (BeanInfo bean : validationPhase.getContext().get(BuildExtension.Key.BEANS)) {
            if (bean.isClassBean()) {
                // TODO: inherited business methods?
                for (MethodInfo method : bean.getTarget().get().asClass().methods()) {
                    List<AnnotationInstance> routes = new LinkedList<>();
                    AnnotationInstance routeAnnotation = annotationStore.getAnnotation(method, ROUTE);
                    if (routeAnnotation != null) {
                        validateMethod(bean, method);
                        routes.add(routeAnnotation);
                    }
                    if (routes.isEmpty()) {
                        AnnotationInstance routesAnnotation = annotationStore.getAnnotation(method, ROUTES);
                        if (routesAnnotation != null) {
                            validateMethod(bean, method);
                            Collections.addAll(routes, routesAnnotation.value().asNestedArray());
                        }
                    }
                    if (!routes.isEmpty()) {
                        LOGGER.debugf("Found route handler business method %s declared on %s", method, bean);
                        routeHandlerBusinessMethods.produce(new AnnotatedRouteHandlerBuildItem(bean, method, routes));
                    }
                }
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    BodyHandlerBuildItem bodyHandler(VertxWebRecorder recorder, HttpConfiguration httpConfiguration) {
        return new BodyHandlerBuildItem(recorder.createBodyHandler(httpConfiguration));
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void addAdditionalRoutes(
            VertxWebRecorder recorder,
            List<AnnotatedRouteHandlerBuildItem> routeHandlerBusinessMethods,
            BuildProducer<GeneratedClassBuildItem> generatedClass,
            AnnotationProxyBuildItem annotationProxy,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClasses,
            BodyHandlerBuildItem bodyHandler,
            BuildProducer<RouteBuildItem> routeProducer) throws IOException {

        ClassOutput classOutput = new ClassOutput() {
            @Override
            public void write(String name, byte[] data) {
                generatedClass.produce(new GeneratedClassBuildItem(true, name, data));
            }
        };
        for (AnnotatedRouteHandlerBuildItem businessMethod : routeHandlerBusinessMethods) {
            String handlerClass = generateHandler(businessMethod.getBean(), businessMethod.getMethod(), classOutput);
            reflectiveClasses.produce(new ReflectiveClassBuildItem(false, false, handlerClass));
            Handler<RoutingContext> routingHandler = recorder.createHandler(handlerClass);
            for (AnnotationInstance routeAnnotation : businessMethod.getRoutes()) {
                Route route = annotationProxy.builder(routeAnnotation, Route.class).build(classOutput);
                Function<Router, io.vertx.ext.web.Route> routeFunction = recorder.createRouteFunction(route,
                        bodyHandler.getHandler());
                AnnotationValue typeValue = routeAnnotation.value("type");
                HandlerType handlerType = HandlerType.NORMAL;
                if (typeValue != null) {
                    String typeString = typeValue.asEnum();
                    switch (typeString) {
                        case "NORMAL":
                            handlerType = HandlerType.NORMAL;
                            break;
                        case "BLOCKING":
                            handlerType = HandlerType.BLOCKING;
                            break;
                        case "FAILURE":
                            handlerType = HandlerType.FAILURE;
                            break;
                        default:
                            throw new IllegalStateException("Unkown type " + typeString);
                    }
                }
                routeProducer.produce(new RouteBuildItem(routeFunction, routingHandler, handlerType));
            }
        }
    }

    @BuildStep
    AnnotationsTransformerBuildItem annotationTransformer() {
        return new AnnotationsTransformerBuildItem(new AnnotationsTransformer() {

            @Override
            public boolean appliesTo(org.jboss.jandex.AnnotationTarget.Kind kind) {
                return kind == org.jboss.jandex.AnnotationTarget.Kind.CLASS;
            }

            @Override
            public void transform(TransformationContext context) {
                if (context.getAnnotations().isEmpty()) {
                    // Class with no annotations but with a method annotated with @Route
                    if (context.getTarget().asClass().annotations().containsKey(ROUTE)
                            || context.getTarget().asClass().annotations().containsKey(ROUTES)) {
                        LOGGER.debugf(
                                "Found route handler business methods on a class %s with no scope annotation - adding @Singleton",
                                context.getTarget());
                        context.transform().add(Singleton.class).done();
                    }
                }
            }
        });
    }

    private void validateMethod(BeanInfo bean, MethodInfo method) {
        if (!method.returnType().kind().equals(Type.Kind.VOID)) {
            throw new IllegalStateException(
                    String.format("Route handler business method must return void [method: %s, bean: %s]", method, bean));
        }
        List<Type> params = method.parameters();
        boolean hasInvalidParam = true;
        if (params.size() == 1) {
            DotName paramTypeName = params.get(0).name();
            if (ROUTING_CONTEXT.equals(paramTypeName) || RX_ROUTING_CONTEXT.equals(paramTypeName)
                    || ROUTING_EXCHANGE.equals(paramTypeName)) {
                hasInvalidParam = false;
            }
        }
        if (hasInvalidParam) {
            throw new IllegalStateException(String.format(
                    "Route handler business method must accept exactly one parameter of type RoutingContext/RoutingExchange: %s [method: %s, bean: %s]",
                    params, method, bean));
        }
    }

    private String generateHandler(BeanInfo bean, MethodInfo method, ClassOutput classOutput) {

        String baseName;
        if (bean.getImplClazz().enclosingClass() != null) {
            baseName = DotNames.simpleName(bean.getImplClazz().enclosingClass()) + "_"
                    + DotNames.simpleName(bean.getImplClazz().name());
        } else {
            baseName = DotNames.simpleName(bean.getImplClazz().name());
        }
        String targetPackage = DotNames.packageName(bean.getImplClazz().name());

        StringBuilder sigBuilder = new StringBuilder();
        sigBuilder.append(method.name()).append("_").append(method.returnType().name().toString());
        for (Type i : method.parameters()) {
            sigBuilder.append(i.name().toString());
        }
        String generatedName = targetPackage.replace('.', '/') + "/" + baseName + HANDLER_SUFFIX + "_" + method.name() + "_"
                + HashUtil.sha1(sigBuilder.toString());

        ClassCreator invokerCreator = ClassCreator.builder().classOutput(classOutput).className(generatedName)
                .interfaces(Handler.class).build();

        MethodCreator invoke = invokerCreator.getMethodCreator("handle", void.class, Object.class);
        // ArcContainer container = Arc.container();
        // InjectableBean<Foo: bean = container.bean("1");
        // InstanceHandle<Foo> handle = container().instance(bean);
        // handle.get().foo(ctx);
        ResultHandle containerHandle = invoke
                .invokeStaticMethod(MethodDescriptor.ofMethod(Arc.class, "container", ArcContainer.class));
        ResultHandle beanHandle = invoke.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(ArcContainer.class, "bean", InjectableBean.class, String.class),
                containerHandle, invoke.load(bean.getIdentifier()));
        ResultHandle instanceHandle = invoke.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(ArcContainer.class, "instance", InstanceHandle.class, InjectableBean.class),
                containerHandle, beanHandle);
        ResultHandle beanInstanceHandle = invoke
                .invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "get", Object.class), instanceHandle);

        ResultHandle paramHandle;
        MethodDescriptor methodDescriptor;
        if (method.parameters().get(0).name().equals(ROUTING_CONTEXT)) {
            paramHandle = invoke.getMethodParam(0);
            methodDescriptor = MethodDescriptor.ofMethod(bean.getImplClazz().name().toString(), method.name(), void.class,
                    RoutingContext.class);
        } else if (method.parameters().get(0).name().equals(RX_ROUTING_CONTEXT)) {
            paramHandle = invoke.newInstance(
                    MethodDescriptor.ofConstructor(io.vertx.reactivex.ext.web.RoutingContext.class, RoutingContext.class),
                    invoke.getMethodParam(0));
            methodDescriptor = MethodDescriptor.ofMethod(bean.getImplClazz().name().toString(), method.name(), void.class,
                    io.vertx.reactivex.ext.web.RoutingContext.class);
        } else {
            paramHandle = invoke.newInstance(MethodDescriptor.ofConstructor(RoutingExchangeImpl.class, RoutingContext.class),
                    invoke.getMethodParam(0));
            methodDescriptor = MethodDescriptor.ofMethod(bean.getImplClazz().name().toString(), method.name(), void.class,
                    RoutingExchange.class);
        }

        // Invoke the business method handler
        invoke.invokeVirtualMethod(methodDescriptor, beanInstanceHandle, paramHandle);

        // handle.destroy() - destroy dependent instance afterwards
        if (BuiltinScope.DEPENDENT.is(bean.getScope())) {
            invoke.invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "destroy", void.class),
                    instanceHandle);
        }
        invoke.returnValue(null);

        invokerCreator.close();
        return generatedName.replace('/', '.');
    }
}
