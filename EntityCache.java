import org.antlr.v4.runtime.tree.ParseTree;
import sun.org.mozilla.javascript.internal.Function;

import java.util.*;

public class EntityCache {

    class CacheObject {
        public AbstractType type;

        public CacheObject(AbstractType type) {
            this.type = type;
        }
    }
    class CacheBlockAndObject {
        public ParseTree block;
        public CacheObject object;

        public CacheBlockAndObject(ParseTree block, CacheObject object) {
            this.block = block;
            this.object = object;
        }
    }
    class CacheBlockAndExpression {
        public ParseTree block;
        public Expression expression;

        public CacheBlockAndExpression(ParseTree block, Expression expression) {
            this.block = block;
            this.expression = expression;
        }
    }

    private Map<ParseTree, Map<String, CacheObject>> cache = new HashMap<ParseTree, Map<String, CacheObject>>();

    static public boolean isStructureBlock(ParseTree node) {
        return node instanceof SwiftParser.Class_bodyContext || node instanceof SwiftParser.Struct_bodyContext;
    }

    public ParseTree findNearestAncestorBlock(ParseTree node) {
        boolean isBlock =
                node instanceof SwiftParser.Top_levelContext ||
                node instanceof SwiftParser.Code_blockContext ||
                node instanceof SwiftParser.Closure_expressionContext ||
                isStructureBlock(node);
        if(isBlock) return node;
        if(node == null || node.getParent() == null || node.getParent() == node) return null;
        return findNearestAncestorBlock(node.getParent());
    }
    public ParseTree findNearestAncestorFunctionBlock(ParseTree node) {
        boolean isBlock =
                node instanceof SwiftParser.Top_levelContext ||
                node instanceof SwiftParser.Function_bodyContext ||
                node instanceof SwiftParser.Initializer_bodyContext;
        if(isBlock) return node;
        if(node == null || node.getParent() == null || node.getParent() == node) return null;
        return findNearestAncestorFunctionBlock(node.getParent());
    }

    public CacheBlockAndObject findNearestAncestorStructure(ParseTree node) {
        if(isStructureBlock(node)) return getClassDefinition(node);
        if(node == null || node.getParent() == null || node.getParent() == node) return null;
        return findNearestAncestorStructure(node.getParent());
    }

    public CacheBlockAndObject getClassDefinition(ParseTree block) {
        if(block instanceof SwiftParser.Class_bodyContext) {
            SwiftParser.Class_declarationContext classDeclaration = (SwiftParser.Class_declarationContext)((SwiftParser.Class_bodyContext)block).parent;
            String className = classDeclaration.class_name().getText();
            return find(className, classDeclaration);
        }
        else {
            SwiftParser.Struct_declarationContext structDeclaration = (SwiftParser.Struct_declarationContext)((SwiftParser.Struct_bodyContext)block).parent;
            String className = structDeclaration.struct_name().getText();
            return find(className, structDeclaration);
        }
    }

    public CacheBlockAndObject find(String varName, ParseTree node) {
        varName = varName.trim();

        do {
            Map<String, CacheObject> blockTypeCache = cache.get(node);
            if(blockTypeCache == null) continue;
            if(blockTypeCache.containsKey(varName)) return new CacheBlockAndObject(node, blockTypeCache.get(varName));
            if(node instanceof SwiftParser.Top_levelContext) break;
        }
        while((node = findNearestAncestorBlock(node.getParent())) != null);

        return null;
    }

    public CacheBlockAndObject findLoose(String varName, ParseTree node) {

        if(varName.equals("self")) return findNearestAncestorStructure(node);

        if(varName.equals("super")) return ((NestedByIndexType)findNearestAncestorStructure(node).object.type).superClass;

        CacheBlockAndObject blockAndObject = find(varName, node);
        if(blockAndObject == null) {
            Map<String, CacheBlockAndObject> candidates = getFunctionsStartingWith(varName, node);
            if(candidates.size() == 0) {
                CacheBlockAndExpression variadicFunction = getFunctionEndingWithVariadic(varName, node);
                if(variadicFunction == null) return null;
                return new CacheBlockAndObject(variadicFunction.block, new CacheObject(variadicFunction.expression.type));
            }
            if(candidates.size() > 1) System.out.println("//Found more than 1 candidate for " + varName);
            return candidates.get(candidates.keySet().toArray()[0]);
        }
        return blockAndObject;
    }

    public Map<String, CacheBlockAndObject> getFunctionsStartingWith(String varName, ParseTree node) {
        Map<String, CacheBlockAndObject> matches = new HashMap<String, CacheBlockAndObject>();

        while((node = findNearestAncestorBlock(node.getParent())) != null) {
            Map<String, CacheObject> blockTypeCache = cache.get(node);
            if(blockTypeCache == null) continue;
            for(Map.Entry<String, CacheObject> iterator:blockTypeCache.entrySet()) {
                if(FunctionUtil.functionStartsWith(iterator.getKey(), iterator.getValue().type, varName)) {
                    matches.put(iterator.getKey(), new CacheBlockAndObject(node, iterator.getValue()));
                }
            }
            if(node instanceof SwiftParser.Top_levelContext) break;
        }
        return matches;
    }
    public Map<String, FunctionType> getFunctionTypesStartingWith(String varName, ParseTree node) {
        Map<String, FunctionType> types = new HashMap<String, FunctionType>();
        Map<String, CacheBlockAndObject> objects = getFunctionsStartingWith(varName.trim(), node);
        for(Map.Entry<String, CacheBlockAndObject> iterator:objects.entrySet()) {
            types.put(iterator.getKey(), (FunctionType)iterator.getValue().object.type);
        }
        return types;
    }

    public CacheBlockAndExpression getFunctionEndingWithVariadic(String varName, ParseTree node) {
        ArrayList<String> variadicNames = FunctionUtil.getVariadicNames(varName);
        for(int i = 0; i < variadicNames.size(); i+=2) {
            CacheBlockAndObject cache = find(variadicNames.get(i), node);
            if(cache != null && cache.object.type instanceof FunctionType) {
                List<AbstractType> parameterTypes = ((FunctionType)cache.object.type).parameterTypes;
                if(!parameterTypes.get(parameterTypes.size() - 1).resulting(null).swiftType().equals(variadicNames.get(i + 1).split("_")[1])) continue;
                return new CacheBlockAndExpression(cache.block, new Expression(variadicNames.get(i), cache.object.type));
            }
        }
        return null;
    }

    public void cacheOne(String identifier, AbstractType type, ParseTree ctx) {
        //System.out.println("Caching " + identifier + " as " + type.swiftType());

        ParseTree nearestAncestorBlock = findNearestAncestorBlock(ctx);

        if(isStructureBlock(nearestAncestorBlock)) {
            //save the variable under class definition too
            CacheBlockAndObject classDefinition = getClassDefinition(nearestAncestorBlock);
            ((NestedByIndexType)classDefinition.object.type).put(identifier, type);
        }

        if(!cache.containsKey(nearestAncestorBlock)) {
            cache.put(nearestAncestorBlock, new HashMap<String, CacheObject>());
        }
        cache.get(nearestAncestorBlock).put(identifier, new CacheObject(type));
    }
}
