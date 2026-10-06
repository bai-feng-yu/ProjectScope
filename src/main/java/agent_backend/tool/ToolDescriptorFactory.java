package agent_backend.tool;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ToolDescriptorFactory {
    public ToolDescriptor create(AgentTool<?, ?> tool) {
        return new ToolDescriptor(
                tool.name(),
                tool.description(),
                schema(tool.inputType()),
                schema(tool.outputType()),
                tool.inputExample(),
                tool.outputExample());
    }

    /**
     * 将 Java 类型映射为 JSON Schema 描述对象（Map 形式），供 LLM 的 function calling 协议使用。
     * <p>
     * 支持的类型及对应的 JSON Schema：
     * <ul>
     *   <li>{@code List<T>}          → {@code {"type":"array", "items": schema(T)}}</li>
     *   <li>{@code String/char}     → {@code {"type":"string"}}</li>
     *   <li>{@code boolean}         → {@code {"type":"boolean"}}</li>
     *   <li>{@code 整数原始类型/包装类} → {@code {"type":"integer"}}</li>
     *   <li>{@code 浮点原始类型/包装类} → {@code {"type":"number"}}</li>
     *   <li>{@code Enum}            → {@code {"type":"string", "enum": [所有常量名]}}</li>
     *   <li>{@code Record}          → {@code {"type":"object", "properties": {...}, "required": [...]}}</li>
     *   <li>{@code Object}          → {@code {}}  （不约束，兜底）</li>
     * </ul>
     * <p>
     * 对于 {@code List<T>} 和 {@code Record}，内部类型会递归调用本方法继续生成 schema。
     *
     * @param type 待映射的 Java 类型（通常来自 {@link AgentTool} 的泛型参数）
     * @return JSON Schema 描述，以 {@code Map<String, Object>} 形式返回，后续会被序列化为 JSON
     * @throws IllegalArgumentException 如果传入类型不在支持范围内
     */
    private Map<String, Object> schema(Type type) {
        // List<T>：递归处理元素类型 T，生成 array schema
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == List.class) {
            return Map.of("type", "array", "items", schema(parameterized.getActualTypeArguments()[0]));
        }
        // 经过上面的分支，剩下的都应该是 Class；否则视为不支持的类型系统特性
        if (!(type instanceof Class<?> clazz)) {
            throw new IllegalArgumentException("Unsupported tool schema type: " + type);
        }
        // 字符串 / 字符 → string
        if (clazz == String.class || clazz == Character.class || clazz == char.class) {
            return Map.of("type", "string");
        }
        // 布尔 → boolean
        if (clazz == boolean.class || clazz == Boolean.class) {
            return Map.of("type", "boolean");
        }
        // 整数族（byte/short/int/long 及其包装类）→ integer
        if (clazz == byte.class || clazz == Byte.class || clazz == short.class || clazz == Short.class
                || clazz == int.class || clazz == Integer.class || clazz == long.class || clazz == Long.class) {
            return Map.of("type", "integer");
        }
        // 浮点族（float/double 及其包装类）→ number
        if (clazz == float.class || clazz == Float.class || clazz == double.class || clazz == Double.class) {
            return Map.of("type", "number");
        }
        // 枚举 → string + enum 约束列表，让 LLM 只能从常量里选
        if (clazz.isEnum()) {
            return Map.of("type", "string", "enum",
                    Arrays.stream(clazz.getEnumConstants()).map(Object::toString).toList());
        }
        // Record → object，遍历每个字段递归生成 properties；所有字段默认必填
        if (clazz.isRecord()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (RecordComponent component : clazz.getRecordComponents()) {
                properties.put(component.getName(), schema(component.getGenericType()));
            }
            return Map.of(
                    "type", "object",
                    "properties", properties,
                    "required", List.copyOf(properties.keySet()),
                    "additionalProperties", false);
        }
        // Object.class 作为兜底：返回空 Map 表示不约束，LLM 可以传任意 JSON
        if (clazz == Object.class) {
            return Map.of();
        }
        // 其他所有类型（Map、普通 class 等）均不支持
        throw new IllegalArgumentException("Unsupported tool schema type: " + clazz.getName());
    }
}