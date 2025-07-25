package com.thorn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.thorn.TokenType.*;

class Parser {
    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    private int current = 0;
    private boolean inClassMethod = false;
    private boolean inConstructor = false;

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    List<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();
        while (!isAtEnd()) {
            Stmt stmt = declaration();
            if (stmt != null) {
                statements.add(stmt);
            } else {
                // If declaration() returned null, there was an error, stop parsing
                break;
            }
            
            // Stop parsing after first error to prevent cascading errors
            if (Thorn.hadError) {
                break;
            }
        }
        return statements;
    }

    private Stmt declaration() {
        try {
            if (match(EXPORT)) return exportDeclaration();
            if (match(IMPORT)) return importDeclaration();
            if (match(CLASS)) return classDeclaration();
            if (match(DOLLAR)) return function("function");
            if (match(AT)) return varDeclaration(true);
            
            // Check for type alias: % IDENTIFIER = 
            if (check(PERCENT) && checkAhead(IDENTIFIER)) {
                advance(); // consume %
                return typeAliasDeclaration();
            }
            
            // Check for typed variable declaration: identifier : type = value
            if (check(IDENTIFIER) && checkAhead(COLON)) {
                return varDeclaration(false);
            }
            
            return statement();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt exportDeclaration() {
        if (match(DOLLAR)) {
            Stmt declaration = function("function");
            return new Stmt.Export(declaration);
        } else if (match(CLASS)) {
            Stmt declaration = classDeclaration();
            return new Stmt.Export(declaration);
        } else if (match(AT)) {
            Stmt declaration = varDeclaration(true);
            return new Stmt.Export(declaration);
        } else if (check(IDENTIFIER)) {
            Token name = advance();
            
            // Check if this is a variable declaration (has : or =) or just an identifier export
            if (match(COLON, EQUAL)) {
                // This is a variable declaration, back up and parse it properly
                current -= 2; // Back up past both the matched token and the identifier
                Stmt declaration = varDeclaration(false);
                return new Stmt.Export(declaration);
            } else {
                // This is just exporting an existing identifier
                consume(SEMICOLON, "Expected ';' after export identifier.");
                return new Stmt.ExportIdentifier(name);
            }
        } else {
            throw error(previous(), "Expected function, class, or variable after 'export'.");
        }
    }

    private Stmt importDeclaration() {
        if (match(LEFT_BRACE)) {
            // import { a, b } from "module"
            List<Token> names = new ArrayList<>();
            do {
                consume(IDENTIFIER, "Expected identifier.");
                names.add(previous());
            } while (match(COMMA));
            consume(RIGHT_BRACE, "Expected '}' after import list.");
            consume(FROM, "Expected 'from' after import list.");
            consume(STRING, "Expected module name.");
            Token module = previous();
            consume(SEMICOLON, "Expected ';' after import statement.");
            return new Stmt.Import(module, names);
        } else {
            // import "module"
            consume(STRING, "Expected module name.");
            Token module = previous();
            consume(SEMICOLON, "Expected ';' after import statement.");
            return new Stmt.Import(module, null);
        }
    }

    private Stmt classDeclaration() {
        Token name = consume(IDENTIFIER, "Expected class name.");
        
        // Parse type parameters if present (e.g., class Box[T])
        List<Stmt.TypeParameter> typeParams = null;
        if (match(LEFT_BRACKET)) {
            typeParams = parseTypeParameters();
            consume(RIGHT_BRACKET, "Expected ']' after type parameters.");
        }
        
        consume(LEFT_BRACE, "Expected '{' before class body.");

        List<Stmt.Function> methods = new ArrayList<>();
        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            if (match(DOLLAR)) {
                methods.add((Stmt.Function) function("method"));
            } else {
                throw error(peek(), "Expected method declaration.");
            }
        }

        consume(RIGHT_BRACE, "Expected '}' after class body.");
        return new Stmt.Class(name, typeParams, methods);
    }

    private Stmt varDeclaration(boolean isImmutable) {
        if (isImmutable) {
            consume(IMMUT, "Expected 'immut' after '@'.");
        }
        
        Token name = consume(IDENTIFIER, "Expected variable name.");
        
        Expr type = null;
        if (match(COLON)) {
            type = parseType();
        }
        
        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expected ';' after variable declaration.");
        
        // If we're inside a constructor and have an initializer, create a property assignment
        if (inConstructor && initializer != null) {
            // Create this.name = value
            Expr thisExpr = new Expr.This(new Token(THIS, "this", null, name.line));
            Expr setExpr = new Expr.Set(thisExpr, name, initializer);
            return new Stmt.Expression(setExpr);
        }
        
        return new Stmt.Var(name, type, initializer, isImmutable);
    }
    
    private Stmt typeAliasDeclaration() {
        Token name = consume(IDENTIFIER, "Expected type alias name.");
        consume(EQUAL, "Expected '=' after type alias name.");
        Expr type = parseType();
        consume(SEMICOLON, "Expected ';' after type alias declaration.");
        return new Stmt.TypeAlias(name, type);
    }

    private Stmt statement() {
        if (match(IF)) return ifStatement();
        if (match(RETURN)) return returnStatement();
        if (match(THROW)) return throwStatement();
        if (match(WHILE)) return whileStatement();
        if (match(FOR)) return forStatement();
        if (match(LEFT_BRACE)) return new Stmt.Block(block());

        return expressionStatement();
    }

    private Stmt ifStatement() {
        consume(LEFT_PAREN, "Expected '(' after 'if'.");
        Expr condition = expression();
        consume(RIGHT_PAREN, "Expected ')' after if condition.");

        Stmt thenBranch = statement();
        Stmt elseBranch = null;
        if (match(ELSE)) {
            elseBranch = statement();
        }

        return new Stmt.If(condition, thenBranch, elseBranch);
    }

    private Stmt returnStatement() {
        Token keyword = previous();
        Expr value = null;
        if (!check(SEMICOLON)) {
            value = expression();
        }

        consume(SEMICOLON, "Expected ';' after return value.");
        return new Stmt.Return(keyword, value);
    }

    private Stmt throwStatement() {
        Token keyword = previous();
        Expr value = null;
        if (!check(SEMICOLON)) {
            value = expression();
        }

        consume(SEMICOLON, "Expected ';' after throw value.");
        return new Stmt.Throw(keyword, value);
    }

    private Stmt whileStatement() {
        consume(LEFT_PAREN, "Expected '(' after 'while'.");
        Expr condition = expression();
        consume(RIGHT_PAREN, "Expected ')' after condition.");
        Stmt body = statement();

        return new Stmt.While(condition, body);
    }

    private Stmt forStatement() {
        consume(LEFT_PAREN, "Expected '(' after 'for'.");
        
        // Check for for-in loop
        if (check(IDENTIFIER) && checkAhead(IN)) {
            Token variable = consume(IDENTIFIER, "Expected variable name.");
            consume(IN, "Expected 'in'.");
            Expr iterable = expression();
            consume(RIGHT_PAREN, "Expected ')' after for clause.");
            Stmt body = statement();
            return new Stmt.For(variable, iterable, body);
        }
        
        // Traditional for loop - convert to while
        Stmt initializer;
        if (match(SEMICOLON)) {
            initializer = null;
        } else {
            initializer = expressionStatement();
        }

        Expr condition = null;
        if (!check(SEMICOLON)) {
            condition = expression();
        }
        consume(SEMICOLON, "Expected ';' after loop condition.");

        Expr increment = null;
        if (!check(RIGHT_PAREN)) {
            increment = expression();
        }
        consume(RIGHT_PAREN, "Expected ')' after for clauses.");

        Stmt body = statement();

        if (increment != null) {
            body = new Stmt.Block(Arrays.asList(body, new Stmt.Expression(increment)));
        }

        if (condition == null) condition = new Expr.Literal(true);
        body = new Stmt.While(condition, body);

        if (initializer != null) {
            body = new Stmt.Block(Arrays.asList(initializer, body));
        }

        return body;
    }

    private List<Stmt> block() {
        List<Stmt> statements = new ArrayList<>();

        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            Stmt stmt = declaration();
            if (stmt != null) {
                statements.add(stmt);
            }
            
            // Stop parsing block after first error to prevent cascading errors
            if (Thorn.hadError) {
                break;
            }
        }

        // Only consume the closing brace if there wasn't an error
        if (!Thorn.hadError) {
            consume(RIGHT_BRACE, "Expected '}' after block.");
        }
        
        return statements;
    }

    private Stmt function(String kind) {
        Token name = consume(IDENTIFIER, "Expected " + kind + " name.");
        
        // Parse type parameters if present (e.g., function[T, R])
        List<Stmt.TypeParameter> typeParams = null;
        if (match(LEFT_BRACKET)) {
            typeParams = parseTypeParameters();
            consume(RIGHT_BRACKET, "Expected ']' after type parameters.");
        }
        
        consume(LEFT_PAREN, "Expected '(' after " + kind + " name.");
        
        List<Stmt.Parameter> parameters = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameters.size() >= 255) {
                    error(peek(), "Can't have more than 255 parameters.");
                }
                
                Token paramName = consume(IDENTIFIER, "Expected parameter name.");
                Expr paramType = null;
                if (match(COLON)) {
                    paramType = parseType();
                }
                
                parameters.add(new Stmt.Parameter(paramName, paramType));
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expected ')' after parameters.");

        Expr returnType = null;
        if (match(COLON)) {
            returnType = parseType();
        } else if (match(ARROW)) {
            returnType = parseType();
        }

        consume(LEFT_BRACE, "Expected '{' before " + kind + " body.");
        
        // Track when we're inside a class method
        boolean wasInClassMethod = inClassMethod;
        boolean wasInConstructor = inConstructor;
        if (kind.equals("method")) {
            inClassMethod = true;
            // Check if this is the constructor (init method)
            if (name.lexeme.equals("init")) {
                inConstructor = true;
            }
        }
        
        List<Stmt> body = block();
        
        // Restore previous context
        inClassMethod = wasInClassMethod;
        inConstructor = wasInConstructor;
        
        return new Stmt.Function(name, typeParams, parameters, returnType, body);
    }

    private Stmt expressionStatement() {
        Expr expr = expression();
        
        // Better error message for missing semicolon 
        if (check(RIGHT_BRACE) || check(RETURN) || check(IF) || check(WHILE) || check(FOR) || check(IDENTIFIER)) {
            // Create a helpful error message pointing to the end of the expression
            Token prevToken = previous();
            throw error(prevToken, "Expected ';' after expression.");
        }
        
        consume(SEMICOLON, "Expected ';' after expression.");
        
        // If we're in a constructor and this is an assignment to a simple variable,
        // convert it to a property assignment
        if (inConstructor && expr instanceof Expr.Assign) {
            Expr.Assign assign = (Expr.Assign) expr;
            // Create this.name = value
            Expr thisExpr = new Expr.This(new Token(THIS, "this", null, assign.name.line));
            Expr setExpr = new Expr.Set(thisExpr, assign.name, assign.value);
            return new Stmt.Expression(setExpr);
        }
        
        return new Stmt.Expression(expr);
    }

    private Expr expression() {
        return assignment();
    }

    private Expr assignment() {
        Expr expr = nullCoalescing();

        if (match(EQUAL, PLUS_EQUAL, MINUS_EQUAL, STAR_EQUAL, SLASH_EQUAL, PERCENT_EQUAL)) {
            Token equals = previous();
            Expr value = assignment();

            if (expr instanceof Expr.Variable) {
                Token name = ((Expr.Variable)expr).name;
                
                // Handle compound assignments
                if (equals.type != EQUAL) {
                    TokenType op = null;
                    switch (equals.type) {
                        case PLUS_EQUAL: op = PLUS; break;
                        case MINUS_EQUAL: op = MINUS; break;
                        case STAR_EQUAL: op = STAR; break;
                        case SLASH_EQUAL: op = SLASH; break;
                        case PERCENT_EQUAL: op = PERCENT; break;
                    }
                    value = new Expr.Binary(expr, new Token(op, equals.lexeme, null, equals.line), value);
                }
                
                return new Expr.Assign(name, value);
            } else if (expr instanceof Expr.Get) {
                Expr.Get get = (Expr.Get)expr;
                return new Expr.Set(get.object, get.name, value);
            } else if (expr instanceof Expr.Index) {
                // Handle array/dict assignment
                Expr.Index index = (Expr.Index)expr;
                return new Expr.IndexSet(index.object, index.bracket, index.index, value);
            }

            error(equals, "Invalid assignment target.");
        }

        return expr;
    }

    private Expr nullCoalescing() {
        Expr expr = or();

        while (match(QUESTION_QUESTION)) {
            Token operator = previous();
            Expr right = or();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr or() {
        Expr expr = and();

        while (match(OR_OR)) {
            Token operator = previous();
            Expr right = and();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        Expr expr = match_();

        while (match(AND_AND)) {
            Token operator = previous();
            Expr right = match_();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr match_() {
        if (match(MATCH)) {
            consume(LEFT_PAREN, "Expected '(' after 'match'.");
            Expr expr = expression();
            consume(RIGHT_PAREN, "Expected ')' after match expression.");
            consume(LEFT_BRACE, "Expected '{' before match cases.");

            List<Expr.Match.Case> cases = new ArrayList<>();
            while (!check(RIGHT_BRACE) && !isAtEnd()) {
                Expr pattern;
                if (match(UNDERSCORE)) {
                    pattern = new Expr.Literal(null); // default case
                } else {
                    pattern = expression();
                }

                Expr guard = null;
                if (match(IF)) {
                    guard = expression();
                }

                consume(ARROW, "Expected '=>' after pattern.");
                
                // Check if this is a block case or expression case
                if (check(LEFT_BRACE)) {
                    // Block case
                    advance(); // consume '{'
                    List<Stmt> stmts = new ArrayList<>();
                    while (!check(RIGHT_BRACE) && !isAtEnd()) {
                        stmts.add(declaration());
                    }
                    consume(RIGHT_BRACE, "Expected '}' after match case block.");
                    consume(COMMA, "Expected ',' after case block.");
                    
                    cases.add(new Expr.Match.Case(pattern, guard, stmts));
                } else {
                    // Expression case
                    Expr value = expression();
                    consume(COMMA, "Expected ',' after case value.");
                    
                    cases.add(new Expr.Match.Case(pattern, guard, value));
                }
            }

            consume(RIGHT_BRACE, "Expected '}' after match cases.");
            return new Expr.Match(expr, cases);
        }

        return equality();
    }

    private Expr equality() {
        Expr expr = comparison();

        while (match(BANG_EQUAL, EQUAL_EQUAL)) {
            Token operator = previous();
            Expr right = comparison();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr comparison() {
        Expr expr = term();

        while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
            Token operator = previous();
            Expr right = term();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr term() {
        Expr expr = factor();

        while (match(MINUS, PLUS)) {
            Token operator = previous();
            Expr right = factor();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr factor() {
        Expr expr = power();

        while (match(SLASH, STAR, PERCENT)) {
            Token operator = previous();
            Expr right = power();
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr power() {
        Expr expr = unary();

        if (match(STAR_STAR)) {
            Token operator = previous();
            Expr right = power(); // Right associative
            expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr unary() {
        if (match(BANG, MINUS)) {
            Token operator = previous();
            Expr right = unary();
            return new Expr.Unary(operator, right);
        }

        return call();
    }

    private Expr call() {
        Expr expr = primary();

        while (true) {
            if (match(LEFT_PAREN)) {
                expr = finishCall(expr, null);
            } else if (match(DOT)) {
                Token name = consume(IDENTIFIER, "Expected property name after '.'.");
                expr = new Expr.Get(expr, name);
            } else if (match(LEFT_BRACKET)) {
                Token bracket = previous();
                
                // Try to parse as type arguments first
                int savedCurrent = current;
                boolean isTypeArgs = false;
                
                // Check if this looks like type arguments
                if (check(IDENTIFIER) || check(STRING_TYPE) || check(NUMBER_TYPE) || 
                    check(BOOLEAN_TYPE) || check(NULL_TYPE) || check(ANY_TYPE) || 
                    check(VOID_TYPE) || check(FUNCTION_TYPE) || check(ARRAY_TYPE)) {
                    // Look ahead to see if this could be type arguments
                    // We need to check if the pattern matches type args followed by (
                    while (!isAtEnd() && !check(RIGHT_BRACKET)) {
                        advance();
                        if (check(COMMA)) {
                            advance();
                        }
                    }
                    if (match(RIGHT_BRACKET) && check(LEFT_PAREN)) {
                        // This is type arguments followed by a call
                        isTypeArgs = true;
                    }
                }
                
                // Reset to start of bracket
                current = savedCurrent;
                
                if (isTypeArgs) {
                    // Parse type arguments
                    List<Expr> typeArgs = new ArrayList<>();
                    do {
                        typeArgs.add(parseType());
                    } while (match(COMMA));
                    consume(RIGHT_BRACKET, "Expected ']' after type arguments.");
                    
                    // Must be followed by a function call
                    if (match(LEFT_PAREN)) {
                        expr = finishCall(expr, typeArgs);
                    } else {
                        throw error(peek(), "Expected '(' after type arguments.");
                    }
                } else {
                    // Parse as array index or slice
                    Expr start = null;
                    if (!check(COLON)) {
                        start = expression();
                    }
                    
                    if (match(COLON)) {
                        // This is a slice
                        Expr end = null;
                        if (!check(RIGHT_BRACKET)) {
                            end = expression();
                        }
                        consume(RIGHT_BRACKET, "Expected ']' after slice.");
                        expr = new Expr.Slice(expr, bracket, start, end);
                    } else {
                        // Regular index
                        consume(RIGHT_BRACKET, "Expected ']' after index.");
                        expr = new Expr.Index(expr, bracket, start);
                    }
                }
            } else {
                break;
            }
        }

        return expr;
    }

    private Expr finishCall(Expr callee, List<Expr> typeArguments) {
        List<Expr> arguments = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (arguments.size() >= 255) {
                    error(peek(), "Can't have more than 255 arguments.");
                }
                arguments.add(expression());
            } while (match(COMMA));
        }

        Token paren = consume(RIGHT_PAREN, "Expected ')' after arguments.");

        return new Expr.Call(callee, paren, typeArguments, arguments);
    }

    private Expr primary() {
        if (match(FALSE)) return new Expr.Literal(false);
        if (match(TRUE)) return new Expr.Literal(true);
        if (match(NULL)) return new Expr.Literal(null);

        if (match(NUMBER, STRING)) {
            return new Expr.Literal(previous().literal);
        }

        if (match(THIS)) return new Expr.This(previous());

        if (match(IDENTIFIER)) {
            return new Expr.Variable(previous());
        }

        if (match(LEFT_PAREN)) {
            Expr expr = expression();
            consume(RIGHT_PAREN, "Expected ')' after expression.");
            return new Expr.Grouping(expr);
        }

        if (match(LEFT_BRACKET)) {
            List<Expr> elements = new ArrayList<>();
            if (!check(RIGHT_BRACKET)) {
                do {
                    elements.add(expression());
                } while (match(COMMA));
            }
            consume(RIGHT_BRACKET, "Expected ']' after list elements.");
            return new Expr.ListExpr(elements);
        }

        if (match(LEFT_BRACE)) {
            List<Expr> keys = new ArrayList<>();
            List<Expr> values = new ArrayList<>();
            if (!check(RIGHT_BRACE)) {
                do {
                    if (match(STRING)) {
                        keys.add(new Expr.Literal(previous().literal));
                    } else {
                        throw error(peek(), "Expected string key in dictionary.");
                    }
                    consume(COLON, "Expected ':' after dictionary key.");
                    values.add(expression());
                } while (match(COMMA));
            }
            consume(RIGHT_BRACE, "Expected '}' after dictionary elements.");
            return new Expr.Dict(keys, values);
        }

        if (match(DOLLAR)) {
            return lambda();
        }

        throw error(peek(), "Expected expression.");
    }

    private Expr lambda() {
        consume(LEFT_PAREN, "Expected '(' after '$'.");
        
        List<Token> parameters = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                parameters.add(consume(IDENTIFIER, "Expected parameter name."));
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expected ')' after parameters.");
        
        consume(ARROW, "Expected '=>' after lambda parameters.");
        
        List<Stmt> body;
        if (match(LEFT_BRACE)) {
            body = block();
        } else {
            // Single expression lambda
            Expr expr = expression();
            body = Arrays.asList(new Stmt.Return(null, expr));
        }
        
        return new Expr.Lambda(parameters, body);
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type == type;
    }

    private boolean checkNext(TokenType type) {
        if (isAtEnd()) return false;
        if (current + 1 >= tokens.size()) return false;
        return tokens.get(current + 1).type == type;
    }

    private boolean checkAhead(TokenType type) {
        int i = current;
        int braceDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        
        while (i < tokens.size() - 1) {
            i++;
            TokenType currentType = tokens.get(i).type;
            
            // Track nesting levels
            if (currentType == LEFT_BRACE) braceDepth++;
            else if (currentType == RIGHT_BRACE) braceDepth--;
            else if (currentType == LEFT_PAREN) parenDepth++;
            else if (currentType == RIGHT_PAREN) parenDepth--;
            else if (currentType == LEFT_BRACKET) bracketDepth++;
            else if (currentType == RIGHT_BRACKET) bracketDepth--;
            
            // Only consider colons at top level (not nested)
            if (currentType == type && braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                return true;
            }
            
            // Stop at semicolon (end of statement)
            if (currentType == SEMICOLON) return false;
            
            // Stop at expression boundaries that indicate this isn't a variable declaration
            if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                if (currentType == DOT || currentType == LEFT_PAREN || currentType == LEFT_BRACKET) {
                    return false;
                }
            }
        }
        return false;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    // Type parsing methods
    private Expr parseType() {
        if (match(STRING_TYPE, NUMBER_TYPE, BOOLEAN_TYPE, NULL_TYPE, ANY_TYPE, VOID_TYPE)) {
            return new Expr.Type(previous());
        }
        
        if (match(ARRAY_TYPE)) {
            Token arrayToken = previous();
            consume(LEFT_BRACKET, "Expected '[' after 'Array'.");
            Expr elementType = parseType();
            consume(RIGHT_BRACKET, "Expected ']' after array element type.");
            return new Expr.GenericType(arrayToken, java.util.Arrays.asList(elementType));
        }
        
        if (match(DICT_TYPE)) {
            Token dictToken = previous();
            consume(LEFT_BRACKET, "Expected '[' after 'Dict'.");
            Expr keyType = parseType();
            consume(COMMA, "Expected ',' between key and value types.");
            Expr valueType = parseType();
            consume(RIGHT_BRACKET, "Expected ']' after dict value type.");
            return new Expr.GenericType(dictToken, java.util.Arrays.asList(keyType, valueType));
        }
        
        if (match(FUNCTION_TYPE)) {
            Token functionToken = previous();
            consume(LEFT_BRACKET, "Expected '[' after 'Function'.");
            
            // Check if this is the new tuple syntax: Function[(params...), return]
            if (match(LEFT_PAREN)) {
                // Parse parameter types
                List<Expr> paramTypes = new ArrayList<>();
                if (!check(RIGHT_PAREN)) {
                    do {
                        paramTypes.add(parseType());
                    } while (match(COMMA));
                }
                consume(RIGHT_PAREN, "Expected ')' after function parameter types.");
                
                consume(COMMA, "Expected ',' between parameter tuple and return type.");
                
                // Parse return type
                Expr returnType = parseType();
                
                consume(RIGHT_BRACKET, "Expected ']' after function type.");
                return new Expr.FunctionType(paramTypes, returnType);
            } else {
                // Legacy syntax: Function[ReturnType] - treat as function with no parameters
                Expr returnType = parseType();
                consume(RIGHT_BRACKET, "Expected ']' after function return type.");
                return new Expr.FunctionType(new ArrayList<>(), returnType);
            }
        }
        
        if (match(LEFT_PAREN)) {
            List<Expr> paramTypes = new ArrayList<>();
            if (!check(RIGHT_PAREN)) {
                do {
                    paramTypes.add(parseType());
                } while (match(COMMA));
            }
            consume(RIGHT_PAREN, "Expected ')' after function parameter types.");
            
            consume(ARROW, "Expected '->' after function parameter types.");
            Expr returnType = parseType();
            
            return new Expr.FunctionType(paramTypes, returnType);
        }
        
        // Handle custom types (identifiers)
        if (check(IDENTIFIER)) {
            Token name = advance();
            
            // Check for generic types like List[T]
            if (match(LEFT_BRACKET)) {
                List<Expr> typeArgs = new ArrayList<>();
                if (!check(RIGHT_BRACKET)) {
                    do {
                        typeArgs.add(parseType());
                    } while (match(COMMA));
                }
                consume(RIGHT_BRACKET, "Expected ']' after generic type arguments.");
                return new Expr.GenericType(name, typeArgs);
            }
            
            return new Expr.Type(name);
        }
        
        throw error(peek(), "Expected type.");
    }

    private List<Stmt.TypeParameter> parseTypeParameters() {
        List<Stmt.TypeParameter> typeParams = new ArrayList<>();
        
        do {
            Token paramName = consume(IDENTIFIER, "Expected type parameter name.");
            Expr constraint = null;
            
            // Check for constraint (e.g., T: Comparable)
            if (match(COLON)) {
                constraint = parseType();
            }
            
            typeParams.add(new Stmt.TypeParameter(paramName, constraint));
        } while (match(COMMA));
        
        return typeParams;
    }
    
    private boolean isAtEnd() {
        return peek().type == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private ParseError error(Token token, String message) {
        Thorn.error(token, message);
        return new ParseError();
    }

    private void synchronize() {
        advance();

        while (!isAtEnd()) {
            if (previous().type == SEMICOLON) return;
            
            // Stop synchronizing if we hit a } - this prevents cascading errors
            if (previous().type == RIGHT_BRACE) return;

            switch (peek().type) {
                case CLASS:
                case DOLLAR:
                case AT:
                case FOR:
                case IF:
                case WHILE:
                case RETURN:
                    return;
            }

            advance();
        }
    }
}