package io.quarkus.arc.processor;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import io.quarkus.arc.ComputingCache;
import io.quarkus.arc.processor.AnnotationLiteralProcessor.Key;
import io.quarkus.arc.processor.AnnotationLiteralProcessor.Literal;
import io.quarkus.arc.processor.ResourceOutput.Resource;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.ClassOutput;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.enterprise.util.AnnotationLiteral;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget.Kind;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

/**
 *
 * @author Martin Kouba
 */
public class AnnotationLiteralGenerator extends AbstractGenerator {

    static final String ANNOTATION_LITERAL_SUFFIX = "_AnnotationLiteral";

    static final String SHARED_SUFFIX = "_Shared";

    private static final Logger LOGGER = Logger.getLogger(AnnotationLiteralGenerator.class);

    /**
     *
     * @param beanDeployment
     * @param annotationLiterals
     * @return a collection of resources
     */
    Collection<Resource> generate(String name, BeanDeployment beanDeployment,
            ComputingCache<Key, Literal> annotationLiteralsCache) {
        List<Resource> resources = new ArrayList<>();
        annotationLiteralsCache.forEachEntry((key, literal) -> {
            ResourceClassOutput classOutput = new ResourceClassOutput(literal.isApplicationClass);
            createSharedAnnotationLiteral(classOutput, key, literal);
            resources.addAll(classOutput.getResources());
        });
        return resources;
    }

    static void createSharedAnnotationLiteral(ClassOutput classOutput, Key key, Literal literal) {
        // Ljavax/enterprise/util/AnnotationLiteral<Lcom/foo/MyQualifier;>;Lcom/foo/MyQualifier;
        String signature = String.format("Ljavax/enterprise/util/AnnotationLiteral<L%1$s;>;L%1$s;",
                key.annotationName.toString().replace('.', '/'));
        String generatedName = literal.className.replace('.', '/');

        ClassCreator annotationLiteral = ClassCreator.builder().classOutput(classOutput).className(generatedName)
                .superClass(AnnotationLiteral.class)
                .interfaces(key.annotationName.toString()).signature(signature).build();

        MethodCreator constructor = annotationLiteral.getMethodCreator(Methods.INIT, "V",
                literal.constructorParams.stream().map(m -> m.returnType().name().toString()).toArray());
        constructor.invokeSpecialMethod(MethodDescriptor.ofConstructor(AnnotationLiteral.class), constructor.getThis());

        for (ListIterator<MethodInfo> iterator = literal.constructorParams.listIterator(); iterator.hasNext();) {
            MethodInfo param = iterator.next();
            String returnType = param.returnType().name().toString();
            // field
            annotationLiteral.getFieldCreator(param.name(), returnType).setModifiers(ACC_PRIVATE | ACC_FINAL);
            // constructor param
            constructor.writeInstanceField(FieldDescriptor.of(annotationLiteral.getClassName(), param.name(), returnType),
                    constructor.getThis(),
                    constructor.getMethodParam(iterator.previousIndex()));
            // value method
            MethodCreator value = annotationLiteral.getMethodCreator(param.name(), returnType).setModifiers(ACC_PUBLIC);
            value.returnValue(value.readInstanceField(
                    FieldDescriptor.of(annotationLiteral.getClassName(), param.name(), returnType), value.getThis()));
        }
        constructor.returnValue(null);

        annotationLiteral.close();
        LOGGER.debugf("Shared annotation literal generated: %s", literal.className);
    }

    static void createAnnotationLiteral(ClassOutput classOutput, ClassInfo annotationClass,
            AnnotationInstance annotationInstance,
            String literalName) {

        Map<String, AnnotationValue> annotationValues = annotationInstance.values().stream()
                .collect(Collectors.toMap(AnnotationValue::name, Function.identity()));

        // Ljavax/enterprise/util/AnnotationLiteral<Lcom/foo/MyQualifier;>;Lcom/foo/MyQualifier;
        String signature = String.format("Ljavax/enterprise/util/AnnotationLiteral<L%1$s;>;L%1$s;",
                annotationClass.name().toString().replace('.', '/'));
        String generatedName = literalName.replace('.', '/');

        ClassCreator annotationLiteral = ClassCreator.builder().classOutput(classOutput).className(generatedName)
                .superClass(AnnotationLiteral.class)
                .interfaces(annotationClass.name().toString()).signature(signature).build();

        for (MethodInfo method : annotationClass.methods()) {
            if (method.name().equals(Methods.CLINIT) || method.name().equals(Methods.INIT)) {
                continue;
            }
            MethodCreator valueMethod = annotationLiteral.getMethodCreator(MethodDescriptor.of(method));
            AnnotationValue value = annotationValues.get(method.name());
            if (value == null) {
                value = method.defaultValue();
            }
            if (value == null) {
                throw new IllegalStateException(String.format(
                        "Value is not set for %s.%s(). Most probably an older version of Jandex was used to index an application dependency. Make sure that Jandex 2.1+ is used.",
                        method.declaringClass().name(), method.name()));
            }
            valueMethod.returnValue(loadValue(valueMethod, value, annotationClass, method));
        }
        annotationLiteral.close();
        LOGGER.debugf("Annotation literal generated: %s", literalName);
    }

    static ResultHandle loadValue(BytecodeCreator valueMethod, AnnotationValue value, ClassInfo annotationClass,
            MethodInfo method) {
        ResultHandle retValue;
        switch (value.kind()) {
            case BOOLEAN:
                retValue = valueMethod.load(value.asBoolean());
                break;
            case STRING:
                retValue = valueMethod.load(value.asString());
                break;
            case BYTE:
                retValue = valueMethod.load(value.asByte());
                break;
            case SHORT:
                retValue = valueMethod.load(value.asShort());
                break;
            case LONG:
                retValue = valueMethod.load(value.asLong());
                break;
            case INTEGER:
                retValue = valueMethod.load(value.asInt());
                break;
            case FLOAT:
                retValue = valueMethod.load(value.asFloat());
                break;
            case DOUBLE:
                retValue = valueMethod.load(value.asDouble());
                break;
            case CHARACTER:
                retValue = valueMethod.load(value.asChar());
                break;
            case CLASS:
                retValue = valueMethod.loadClass(value.asClass().toString());
                break;
            case ARRAY:
                retValue = arrayValue(value, valueMethod, method, annotationClass);
                break;
            case ENUM:
                retValue = valueMethod
                        .readStaticField(FieldDescriptor.of(value.asEnumType().toString(), value.asEnum(),
                                value.asEnumType().toString()));
                break;
            case NESTED:
            default:
                throw new UnsupportedOperationException("Unsupported value: " + value);
        }
        return retValue;
    }

    static ResultHandle arrayValue(AnnotationValue value, BytecodeCreator valueMethod, MethodInfo method,
            ClassInfo annotationClass) {
        ResultHandle retValue;
        switch (value.componentKind()) {
            case CLASS:
                Type[] classArray = value.asClassArray();
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(classArray.length));
                for (int i = 0; i < classArray.length; i++) {
                    valueMethod.writeArrayValue(retValue, i, valueMethod.loadClass(classArray[i].name()
                            .toString()));
                }
                break;
            case STRING:
                String[] stringArray = value.asStringArray();
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(stringArray.length));
                for (int i = 0; i < stringArray.length; i++) {
                    valueMethod.writeArrayValue(retValue, i, valueMethod.load(stringArray[i]));
                }
                break;
            case INTEGER:
                int[] intArray = value.asIntArray();
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(intArray.length));
                for (int i = 0; i < intArray.length; i++) {
                    valueMethod.writeArrayValue(retValue, i, valueMethod.load(intArray[i]));
                }
                break;
            case LONG:
                long[] longArray = value.asLongArray();
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(longArray.length));
                for (int i = 0; i < longArray.length; i++) {
                    valueMethod.writeArrayValue(retValue, i, valueMethod.load(longArray[i]));
                }
                break;
            case BYTE:
                byte[] byteArray = value.asByteArray();
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(byteArray.length));
                for (int i = 0; i < byteArray.length; i++) {
                    valueMethod.writeArrayValue(retValue, i, valueMethod.load(byteArray[i]));
                }
                break;
            case CHARACTER:
                char[] charArray = value.asCharArray();
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(charArray.length));
                for (int i = 0; i < charArray.length; i++) {
                    valueMethod.writeArrayValue(retValue, i, valueMethod.load(charArray[i]));
                }
                break;
            // TODO: handle other less common types of array components
            default:
                // Return empty array for empty arrays and unsupported types
                // For an empty array the component kind is UNKNOWN
                if (value.componentKind() != org.jboss.jandex.AnnotationValue.Kind.UNKNOWN) {
                    // Unsupported type - check if it is @Nonbinding, @Nonbinding array members should not be a problem in CDI
                    AnnotationInstance nonbinding = method.annotation(DotNames.NONBINDING);
                    if (nonbinding == null || nonbinding.target()
                            .kind() != Kind.METHOD) {
                        LOGGER.warnf("Unsupported array component type %s on %s - literal returns an empty array", method,
                                annotationClass);
                    }
                }
                retValue = valueMethod.newArray(componentType(method), valueMethod.load(0));
        }
        return retValue;
    }

    static String componentType(MethodInfo method) {
        ArrayType arrayType = method.returnType().asArrayType();
        return arrayType.component().name().toString();
    }

    static String generatedSharedName(DotName annotationName) {
        // when the annotation is a java.lang annotation we need to use a different package in which to generate the literal
        // otherwise a security exception will be thrown when the literal is loaded
        String nameToUse = isJavaLang(annotationName.toString())
                ? AbstractGenerator.DEFAULT_PACKAGE + annotationName.withoutPackagePrefix()
                : annotationName.toString();

        // com.foo.MyQualifier -> com.foo.MyQualifier1_Shared_AnnotationLiteral
        return nameToUse + SHARED_SUFFIX + ANNOTATION_LITERAL_SUFFIX;
    }

    private static boolean isJavaLang(String s) {
        return s.startsWith("java.lang");
    }

    static String generatedLocalName(String targetPackage, String simpleName, String hash) {
        // com.foo.MyQualifier -> com.bar.MyQualifier_somehashvalue_AnnotationLiteral
        return (isJavaLang(targetPackage) ? AbstractGenerator.DEFAULT_PACKAGE : targetPackage) + "." + simpleName + hash
                + AnnotationLiteralGenerator.ANNOTATION_LITERAL_SUFFIX;
    }

}
