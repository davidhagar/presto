/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.aggregation;

import com.facebook.presto.operator.aggregation.state.AccumulatorStateFactory;
import com.facebook.presto.operator.aggregation.state.AccumulatorStateSerializer;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.type.SqlType;
import com.facebook.presto.util.IterableTransformer;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;

import javax.annotation.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.BLOCK_INDEX;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.INPUT_CHANNEL;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.NULLABLE_INPUT_CHANNEL;
import static com.facebook.presto.operator.aggregation.AggregationMetadata.ParameterMetadata.ParameterType.SAMPLE_WEIGHT;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public class AggregationMetadata
{
    public static final Set<Class<?>> SUPPORTED_PARAMETER_TYPES = ImmutableSet.of(Block.class, long.class, double.class, boolean.class, Slice.class);

    private final String name;
    private final List<ParameterMetadata> inputMetadata;
    private final Method inputFunction;
    private final List<ParameterMetadata> intermediateInputMetadata;
    @Nullable
    private final Method intermediateInputFunction;
    @Nullable
    private final Method combineFunction;
    @Nullable
    private final Method outputFunction;
    private final Class<?> stateInterface;
    private final AccumulatorStateSerializer<?> stateSerializer;
    private final AccumulatorStateFactory<?> stateFactory;
    private final Type outputType;
    private final boolean approximate;

    public AggregationMetadata(
            String name,
            List<ParameterMetadata> inputMetadata,
            Method inputFunction,
            @Nullable List<ParameterMetadata> intermediateInputMetadata,
            @Nullable Method intermediateInputFunction,
            @Nullable Method combineFunction,
            @Nullable Method outputFunction,
            Class<?> stateInterface,
            AccumulatorStateSerializer<?> stateSerializer,
            AccumulatorStateFactory<?> stateFactory,
            Type outputType,
            boolean approximate)
    {
        this.outputType = checkNotNull(outputType);
        this.inputMetadata = ImmutableList.copyOf(checkNotNull(inputMetadata, "inputMetadata is null"));
        checkArgument((intermediateInputFunction == null) == (intermediateInputMetadata == null), "intermediate input parameters must be specified iff an intermediate function is provided");
        if (intermediateInputMetadata != null) {
            this.intermediateInputMetadata = ImmutableList.copyOf(intermediateInputMetadata);
        }
        else {
            this.intermediateInputMetadata = null;
        }
        this.name = checkNotNull(name, "name is null");
        this.inputFunction = checkNotNull(inputFunction, "inputFunction is null");
        checkArgument(combineFunction == null || intermediateInputFunction == null, "Aggregation cannot have both a combine and a intermediate input method");
        checkArgument(combineFunction != null || intermediateInputFunction != null, "Aggregation must have either a combine or a intermediate input method");
        this.intermediateInputFunction = intermediateInputFunction;
        this.combineFunction = combineFunction;
        this.outputFunction = outputFunction;
        this.stateInterface = checkNotNull(stateInterface, "stateInterface is null");
        this.stateSerializer = checkNotNull(stateSerializer, "stateSerializer is null");
        this.stateFactory = checkNotNull(stateFactory, "stateFactory is null");
        this.approximate = approximate;

        verifyInputFunctionSignature(inputFunction, inputMetadata, stateInterface);
        if (intermediateInputFunction != null) {
            checkArgument(countInputChannels(intermediateInputMetadata) == 1, "Intermediate input function may only have one input channel");
            verifyInputFunctionSignature(intermediateInputFunction, intermediateInputMetadata, stateInterface);
        }
        if (combineFunction != null) {
            verifyCombineFunction(combineFunction, stateInterface);
        }
        if (approximate) {
            verifyApproximateOutputFunction(outputFunction, stateInterface);
        }
        else {
            verifyExactOutputFunction(outputFunction, stateInterface);
        }
    }

    public Type getOutputType()
    {
        return outputType;
    }

    public List<ParameterMetadata> getInputMetadata()
    {
        return inputMetadata;
    }

    public List<ParameterMetadata> getIntermediateInputMetadata()
    {
        return intermediateInputMetadata;
    }

    public String getName()
    {
        return name;
    }

    public Method getInputFunction()
    {
        return inputFunction;
    }

    @Nullable
    public Method getIntermediateInputFunction()
    {
        return intermediateInputFunction;
    }

    @Nullable
    public Method getCombineFunction()
    {
        return combineFunction;
    }

    @Nullable
    public Method getOutputFunction()
    {
        return outputFunction;
    }

    public Class<?> getStateInterface()
    {
        return stateInterface;
    }

    public AccumulatorStateSerializer<?> getStateSerializer()
    {
        return stateSerializer;
    }

    public AccumulatorStateFactory<?> getStateFactory()
    {
        return stateFactory;
    }

    public boolean isApproximate()
    {
        return approximate;
    }

    private static void verifyInputFunctionSignature(Method method, List<ParameterMetadata> parameterMetadatas, Class<?> stateInterface)
    {
        verifyStaticAndPublic(method);
        Class<?>[] parameters = method.getParameterTypes();
        checkArgument(stateInterface == parameters[0], "First argument of aggregation input function must be %s", stateInterface.getSimpleName());
        checkArgument(parameters.length > 0, "Aggregation input function must have at least one parameter");
        checkArgument(parameterMetadatas.get(0).getParameterType() == ParameterMetadata.ParameterType.STATE, "First parameter must be state");
        for (int i = 1; i < parameters.length; i++) {
            ParameterMetadata metadata = parameterMetadatas.get(i);
            switch (metadata.getParameterType()) {
                case NULLABLE_INPUT_CHANNEL:
                    checkArgument(parameters[i] == Block.class, "Parameter must be Block if it has @Nullable");
                    break;
                case INPUT_CHANNEL:
                    checkArgument(SUPPORTED_PARAMETER_TYPES.contains(parameters[i]), "Unsupported type: %s", parameters[i].getSimpleName());
                    break;
                case BLOCK_INDEX:
                    checkArgument(parameters[i] == int.class, "Block index parameter must be an int");
                    break;
                case SAMPLE_WEIGHT:
                    checkArgument(parameters[i] == long.class, "Sample weight parameter must be a long");
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported parameter: " + metadata.getParameterType());
            }
        }
    }

    private static void verifyStaticAndPublic(Method method)
    {
        checkArgument(Modifier.isStatic(method.getModifiers()), "%s is not static", method.getName());
        checkArgument(Modifier.isPublic(method.getModifiers()), "%s is not public", method.getName());
    }

    private static void verifyCombineFunction(Method method, Class<?> stateInterface)
    {
        verifyStaticAndPublic(method);
        Class<?>[] parameterTypes = method.getParameterTypes();
        checkArgument(parameterTypes.length == 2 && parameterTypes[0] == stateInterface && parameterTypes[1] == stateInterface, "Combine function must have the signature (%s, %s)", stateInterface.getSimpleName(), stateInterface.getSimpleName());
    }

    private static void verifyApproximateOutputFunction(Method method, Class<?> stateInterface)
    {
        checkNotNull(method, "Approximate aggregations must specify an output function");
        verifyStaticAndPublic(method);
        Class<?>[] parameterTypes = method.getParameterTypes();
        checkArgument(parameterTypes.length == 3 && parameterTypes[0] == stateInterface && parameterTypes[1] == double.class && parameterTypes[2] == BlockBuilder.class, "Output function must have the signature (%s, double, BlockBuilder)", stateInterface.getSimpleName());
    }

    private static void verifyExactOutputFunction(Method method, Class<?> stateInterface)
    {
        if (method == null) {
            return;
        }
        verifyStaticAndPublic(method);
        Class<?>[] parameterTypes = method.getParameterTypes();
        checkArgument(parameterTypes.length == 2 && parameterTypes[0] == stateInterface && parameterTypes[1] == BlockBuilder.class, "Output function must have the signature (%s, BlockBuilder)", stateInterface.getSimpleName());
    }

    public static int countInputChannels(List<ParameterMetadata> metadatas)
    {
        int parameters = 0;
        for (ParameterMetadata metadata : metadatas) {
            if (metadata.getParameterType() == INPUT_CHANNEL || metadata.getParameterType() == NULLABLE_INPUT_CHANNEL) {
                parameters++;
            }
        }

        return parameters;
    }

    public static class ParameterMetadata
    {
        private final ParameterType parameterType;
        private final Type sqlType;

        public ParameterMetadata(ParameterType parameterType)
        {
            this(parameterType, null);
        }

        public ParameterMetadata(ParameterType parameterType, Type sqlType)
        {
            checkArgument((sqlType == null) == (parameterType != INPUT_CHANNEL && parameterType != NULLABLE_INPUT_CHANNEL), "sqlType must be provided only for input channels");
            this.parameterType = parameterType;
            this.sqlType = sqlType;
        }

        public static ParameterMetadata fromAnnotations(Annotation[] annotations, String methodName, TypeManager typeManager)
        {
            List<Annotation> baseTypes = IterableTransformer.on(annotations).select(new Predicate<Annotation>()
            {
                @Override
                public boolean apply(@Nullable Annotation input)
                {
                    return input instanceof SqlType || input instanceof BlockIndex || input instanceof SampleWeight;
                }
            }).list();
            boolean nullable = !FluentIterable.from(Arrays.asList(annotations)).filter(NullablePosition.class).isEmpty();
            checkArgument(baseTypes.size() == 1, "Parameter of %s must have exactly one of @SqlType, @BlockIndex, and @SampleWeight", methodName);
            Annotation annotation = baseTypes.get(0);
            checkArgument(!nullable || (annotation instanceof SqlType), "%s contains a parameters with @Nullable that is not @SqlType", methodName);
            if (annotation instanceof SqlType) {
                TypeSignature signature = parseTypeSignature(((SqlType) annotation).value());
                if (nullable) {
                    return new ParameterMetadata(NULLABLE_INPUT_CHANNEL, typeManager.getType(signature));
                }
                else {
                    return new ParameterMetadata(INPUT_CHANNEL, typeManager.getType(signature));
                }
            }
            else if (annotation instanceof BlockIndex) {
                return new ParameterMetadata(BLOCK_INDEX);
            }
            else if (annotation instanceof SampleWeight) {
                return new ParameterMetadata(SAMPLE_WEIGHT);
            }
            else {
                throw new IllegalArgumentException("Unsupported annotation: " + annotation);
            }
        }

        public ParameterType getParameterType()
        {
            return parameterType;
        }

        public Type getSqlType()
        {
            return sqlType;
        }

        public enum ParameterType
        {
            INPUT_CHANNEL,
            NULLABLE_INPUT_CHANNEL,
            BLOCK_INDEX,
            SAMPLE_WEIGHT,
            STATE
        }
    }
}
