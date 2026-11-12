package io.github.subapavel.jqj.internal.parse;

import static java.nio.charset.StandardCharsets.US_ASCII;

import io.github.subapavel.jqj.JqCompileException;
import io.github.subapavel.jqj.internal.json.JsonStrings;
import io.github.subapavel.jqj.query.ir.Source;
import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a filter into its {@link Program} (ADR-0032): recursive descent over jq 1.8.2's grammar ({@code parser.y}),
 * precedence climbing over {@link BinOp} for its {@code Expr}. The levels, loosest first:
 *
 * <pre>
 * query     := unit (',' unit)* ('|' query)?       ',' nests left, '|' right
 * unit      := def query | 'label' $name '|' query
 *            | expr ('as' patterns '|' query)?     the three prefix forms take the rest of the query
 * expr      := term (BinOp term)*                  by BinOp's levels; a non-associative level once
 * term      := '-' term | 'try' term ('catch' term)? | primary postfix*
 * postfix   := FIELD | '.' string | '.'? '[' ... ']' | '?'   each but '?' may end in '?'
 * </pre>
 *
 * <p>The whole text is lexed first, as the lexer's modes need no parser. Errors are collected as jq's Bison parser
 * reports them (ADR-0031, ADR-0032) and thrown together after the parse, before any resolution: a bad escape run's
 * lexical block when the parser reads its token (jq's {@code yylex}); a syntax error at the first token the grammar
 * rejects, {@code syntax error, unexpected <jq's token name>} (DEVIATIONS "Syntax-error text"), at the lexer's last
 * match for the end of the text. After a syntax error the parse goes on only at the two recovery productions jq-ref
 * shows on recorded inputs, each with jq's recovery message: an unusual name after a leading dot ({@code '.' error}
 * and {@code '.' IDENT error}) and an object key expression ({@code error ':' DictExpr}). Every other error ends the
 * parse there, also where jq has a silent recovery production or one jqj does not build ({@code (}, {@code [}, a
 * term's {@code [}, {@code if ... then}, {@code catch}, {@code break} and an object pattern), as no recorded input
 * needs it. As in Bison, a recovery reports no new syntax error until three tokens have been shifted, and drops a
 * token it cannot take.
 */
public final class Parser {
  private static final String TRY_FIELD = "try .[\"field\"] instead of .field for unusually named fields";

  private static final String MAY_NEED_PARENTHESES = "May need parentheses around object key expression";

  /** The kinds that cannot follow a term, so jq's {@code '.' error} takes them after a leading dot. */
  private static final Set<Token.Kind> NO_TERM_AFTER =
      EnumSet.of(
          Token.Kind.REC,
          Token.Kind.ALTERNATION,
          Token.Kind.LPAREN,
          Token.Kind.LBRACE,
          Token.Kind.NUMBER,
          Token.Kind.BINDING,
          Token.Kind.LOC,
          Token.Kind.DOLLAR,
          Token.Kind.INVALID_CHARACTER);

  /** The keywords that may follow a term; any other keyword after a leading dot is jq's {@code '.' error}. */
  private static final Set<String> KEYWORDS_AFTER_A_TERM = Set.of("as", "then", "elif", "else", "end", "and", "or", "catch");

  private final FilterText text;

  /** The filter's UTF-8 bytes, which the tokens index. */
  private final byte[] b;

  /** The text's tokens; the last one is the END, at the lexer's last match, where jq reports the end of the text. */
  private final List<Token> tokens = new ArrayList<>();

  /** The lexer's error block of each INVALID_ESCAPES token, in order, and the next one to report. */
  private final List<String> lexicalBlocks;

  private int nextLexicalBlock;

  /** The index of the current token, jq's lookahead. */
  private int at;

  private Token token;

  /** The error blocks so far, in jq's order, and the position of the first. */
  private final List<String> blocks = new ArrayList<>();

  private int firstLine;
  private int firstColumn;

  /** Bison's {@code yyerrstatus}: 3 after an error, one less per shifted token; a syntax error is reported at 0. */
  private int errorStatus;

  /** Whether the innermost error state of the parse so far recovers (a leading dot, an object) or ends the parse. */
  private boolean recovers;

  private Parser(String filter) {
    Lexer lexer = new Lexer(filter);
    this.text = lexer.text();
    this.b = text.bytes();
    do {
      tokens.add(lexer.next());
    } while (tokens.getLast().kind() != Token.Kind.END);
    tokens.set(tokens.size() - 1, new Token(Token.Kind.END, lexer.lastStart(), lexer.lastEnd()));
    this.lexicalBlocks = lexer.errorBlocks();
    this.token = tokens.getFirst();
  }

  /**
   * The AST of {@code filter}.
   *
   * @throws JqCompileException with every lexical, syntax and recovery error block of the text, in jq's order
   */
  public static Program parse(String filter) {
    Parser parser = new Parser(filter);
    Program program = parser.program();
    if (!parser.blocks.isEmpty()) {
      throw parser.errors();
    }
    return program;
  }

  /** jq's TopLevel: an optional module directive, imports, then a query or definitions alone. */
  private Program program() {
    Ast module = null;
    if (isKeyword("module")) {
      advance();
      module = query();
      expect(Token.Kind.SEMICOLON);
    }
    List<Ast.Import> imports = new ArrayList<>();
    while (isKeyword("import") || isKeyword("include")) {
      imports.add(importDirective());
    }
    List<Ast.FuncDef> defs = new ArrayList<>();
    while (isKeyword("def")) {
      defs.add(funcDef());
    }
    Ast main = null;
    if (token.kind() != Token.Kind.END) {
      main = query();
      if (token.kind() != Token.Kind.END) {
        throw syntaxError();
      }
      for (Ast.FuncDef def : defs.reversed()) {
        main = new Ast.Def(new Source(def.source().start(), main.source().end()), def, main);
      }
      defs.clear();
    }
    return new Program(module, List.copyOf(imports), List.copyOf(defs), main);
  }

  /** {@code import path as name metadata? ;}, {@code import path as $name metadata? ;} or {@code include path metadata? ;}. */
  private Ast.Import importDirective() {
    Token first = advance();
    Ast.Str path = string();
    String name = null;
    boolean data = false;
    if (word(first).equals("import")) {
      expectKeyword("as");
      if (token.kind() == Token.Kind.BINDING) {
        data = true;
        name = variable(advance());
      } else if (token.kind() == Token.Kind.IDENT) {
        name = word(advance());
      } else {
        throw syntaxError();
      }
    }
    Ast metadata = token.kind() == Token.Kind.SEMICOLON ? null : query();
    Token semicolon = expect(Token.Kind.SEMICOLON);
    return new Ast.Import(new Source(first.start(), semicolon.end()), path, name, data, metadata);
  }

  /** {@code def name: body;} or {@code def name(param; ...): body;}, a param a name or a {@code $name}. */
  private Ast.FuncDef funcDef() {
    Token def = advance();
    if (token.kind() != Token.Kind.IDENT) {
      throw syntaxError();
    }
    String name = word(advance());
    List<Ast.Param> params = new ArrayList<>();
    if (token.kind() == Token.Kind.LPAREN) {
      advance();
      for (;;) {
        if (token.kind() == Token.Kind.BINDING) {
          params.add(new Ast.Param(source(token), variable(token), true));
        } else if (token.kind() == Token.Kind.IDENT) {
          params.add(new Ast.Param(source(token), word(token), false));
        } else {
          throw syntaxError();
        }
        advance();
        if (token.kind() != Token.Kind.SEMICOLON) {
          break;
        }
        advance();
      }
      expect(Token.Kind.RPAREN);
    }
    expect(Token.Kind.COLON);
    Ast body = query();
    Token semicolon = expect(Token.Kind.SEMICOLON);
    return new Ast.FuncDef(new Source(def.start(), semicolon.end()), name, List.copyOf(params), body);
  }

  /** {@code unit (',' unit)* ('|' query)?}. */
  private Ast query() {
    Ast left = unit();
    while (token.kind() == Token.Kind.COMMA) {
      advance();
      Ast right = unit();
      left = new Ast.Comma(span(left, right), left, right);
    }
    if (token.kind() != Token.Kind.PIPE) {
      return left;
    }
    advance();
    Ast right = query();
    return new Ast.Pipe(span(left, right), left, right);
  }

  /** A definition, a label or a binding over the rest of the query, or an expression. */
  private Ast unit() {
    if (isKeyword("def")) {
      Ast.FuncDef def = funcDef();
      Ast body = query();
      return new Ast.Def(new Source(def.source().start(), body.source().end()), def, body);
    }
    if (isKeyword("label")) {
      Token label = advance();
      if (token.kind() != Token.Kind.BINDING) {
        throw syntaxError();
      }
      String name = variable(advance());
      expect(Token.Kind.PIPE);
      Ast body = query();
      return new Ast.Label(new Source(label.start(), body.source().end()), name, body);
    }
    Ast value = expr(0);
    if (!isKeyword("as")) {
      return value;
    }
    advance();
    List<Ast.Pattern> patterns = patterns();
    expect(Token.Kind.PIPE);
    Ast body = query();
    return new Ast.Bind(span(value, body), value, patterns, body);
  }

  /**
   * jq's Expr from the operators of level {@code min} up: each operator takes a right operand of the next level up, its
   * own level when it is right-associative, and a second operator of a non-associative level is a syntax error.
   */
  private Ast expr(int min) {
    Ast left = term();
    for (;;) {
      BinOp op = binOp();
      if (op == null || op.level < min) {
        return left;
      }
      advance();
      Ast right = expr(op.assoc == BinOp.Assoc.RIGHT ? op.level : op.level + 1);
      left = new Ast.Binary(span(left, right), op, left, right);
      if (op.assoc == BinOp.Assoc.NONE) {
        BinOp next = binOp();
        if (next != null && next.level == op.level) {
          throw syntaxError();
        }
      }
    }
  }

  /** The operator the current token is, or null. */
  private BinOp binOp() {
    return BinOp.of(token.kind(), token.kind() == Token.Kind.KEYWORD ? word(token) : null);
  }

  /** {@code '-' term}, {@code try} with an optional {@code catch}, each taking a term, or a postfix term. */
  private Ast term() {
    if (token.kind() == Token.Kind.MINUS) {
      Token minus = advance();
      Ast operand = term();
      return new Ast.Neg(new Source(minus.start(), operand.source().end()), operand);
    }
    if (isKeyword("try")) {
      Token start = advance();
      Ast body = term();
      if (!isKeyword("catch")) {
        return new Ast.Try(new Source(start.start(), body.source().end()), body, null);
      }
      advance();
      // jq's "try" Exp "catch" error, not built: an error in the handler ends the parse.
      boolean outer = enter(false);
      Ast handler = term();
      recovers = outer;
      return new Ast.Try(new Source(start.start(), handler.source().end()), body, handler);
    }
    return postfix(primary());
  }

  private Ast primary() {
    Token first = token;
    return switch (token.kind()) {
      case DOT -> dot();
      case REC -> new Ast.Recurse(source(advance()));
      case FIELD -> {
        advance();
        yield field(null, first);
      }
      case NUMBER -> new Ast.Num(source(first), word(advance()));
      case STRING_START -> string();
      case FORMAT -> {
        if (tokens.get(at + 1).kind() == Token.Kind.STRING_START) {
          yield string();
        }
        yield new Ast.Format(source(first), word(advance()).substring(1));
      }
      case LPAREN -> {
        advance();
        // jq's '(' error ')', silent: not built.
        boolean outer = enter(false);
        Ast body = query();
        Token close = expect(Token.Kind.RPAREN);
        recovers = outer;
        yield new Ast.Paren(new Source(first.start(), close.end()), body);
      }
      case LBRACKET -> {
        advance();
        // jq's '[' error ']', silent: not built.
        boolean outer = enter(false);
        Ast body = token.kind() == Token.Kind.RBRACKET ? null : query();
        Token close = expect(Token.Kind.RBRACKET);
        recovers = outer;
        yield new Ast.ArrayCons(new Source(first.start(), close.end()), body);
      }
      case LBRACE -> object();
      case BINDING -> new Ast.Var(source(first), variable(advance()));
      case LOC -> new Ast.Loc(source(advance()));
      case DOLLAR -> {
        advance();
        expect(Token.Kind.DOLLAR);
        expect(Token.Kind.DOLLAR);
        if (token.kind() != Token.Kind.BINDING) {
          throw syntaxError();
        }
        Token name = advance();
        yield new Ast.LastRead(new Source(first.start(), name.end()), variable(name));
      }
      case IDENT -> call();
      case KEYWORD -> keywordTerm();
      default -> throw syntaxError();
    };
  }

  /** {@code name} or {@code name(arg; ...)}, each arg a query. */
  private Ast call() {
    Token name = advance();
    List<Ast> args = new ArrayList<>();
    int end = name.end();
    if (token.kind() == Token.Kind.LPAREN) {
      advance();
      args.add(query());
      while (token.kind() == Token.Kind.SEMICOLON) {
        advance();
        args.add(query());
      }
      end = expect(Token.Kind.RPAREN).end();
    }
    return new Ast.Call(new Source(name.start(), end), word(name), List.copyOf(args));
  }

  /** A term that starts with a keyword: {@code reduce}, {@code foreach}, {@code if} or {@code break}. */
  private Ast keywordTerm() {
    Token start = token;
    return switch (word(start)) {
      case "reduce", "foreach" -> {
        advance();
        Ast input = expr(0);
        expectKeyword("as");
        List<Ast.Pattern> patterns = patterns();
        expect(Token.Kind.LPAREN);
        Ast init = query();
        expect(Token.Kind.SEMICOLON);
        Ast update = query();
        if (word(start).equals("reduce")) {
          Source source = new Source(start.start(), expect(Token.Kind.RPAREN).end());
          yield new Ast.Reduce(source, input, patterns, init, update);
        }
        Ast extract = null;
        if (token.kind() == Token.Kind.SEMICOLON) {
          advance();
          extract = query();
        }
        Source source = new Source(start.start(), expect(Token.Kind.RPAREN).end());
        yield new Ast.Foreach(source, input, patterns, init, update, extract);
      }
      case "if" -> ifTail(advance());
      case "break" -> {
        advance();
        if (token.kind() != Token.Kind.BINDING) {
          // jq's "break" error, with its own message: not built.
          recovers = false;
          throw syntaxError();
        }
        Token name = advance();
        yield new Ast.Break(new Source(start.start(), name.end()), variable(name));
      }
      default -> throw syntaxError();
    };
  }

  /** After {@code if} or {@code elif}: {@code cond then branch}, then {@code elif ...}, {@code else ... end} or {@code end}. */
  private Ast.If ifTail(Token start) {
    Ast cond = query();
    expectKeyword("then");
    // jq's "if" Query "then" error, with its own message: not built.
    boolean outer = enter(false);
    Ast then = query();
    Ast otherwise = null;
    int end;
    if (isKeyword("elif")) {
      otherwise = ifTail(advance());
      end = otherwise.source().end();
    } else {
      if (isKeyword("else")) {
        advance();
        otherwise = query();
      }
      end = expectKeyword("end").end();
    }
    recovers = outer;
    return new Ast.If(new Source(start.start(), end), cond, then, otherwise);
  }

  /** The suffixes after a term, each nesting the term so far as its target. */
  private Ast postfix(Ast term) {
    Ast target = term;
    for (;;) {
      switch (token.kind()) {
        case FIELD -> target = field(target, advance());
        case DOT -> {
          Token dot = advance();
          if (token.kind() == Token.Kind.LBRACKET) {
            target = bracket(target, dot);
          } else {
            Ast.Str name = string();
            boolean optional = token.kind() == Token.Kind.QUESTION;
            int end = optional ? advance().end() : name.source().end();
            target = new Ast.StringField(new Source(dot.start(), end), target, name, optional);
          }
        }
        case LBRACKET -> {
          // jq's Term '[' error ']', silent: not built.
          boolean outer = enter(false);
          target = bracket(target, token);
          recovers = outer;
        }
        case QUESTION -> target = new Ast.Optional(source(advance()), target);
        default -> {
          return target;
        }
      }
    }
  }

  /** The FIELD {@code name} after {@code target} (null for the FIELD alone), and a {@code ?} after it. */
  private Ast.Field field(Ast target, Token name) {
    boolean optional = token.kind() == Token.Kind.QUESTION;
    int end = optional ? advance().end() : name.end();
    return new Ast.Field(new Source(name.start(), end), target, ascii(name.start() + 1, name.end()), optional);
  }

  /** {@code [] [query] [query:] [:query] [query:query]} from the current {@code [}, a {@code ?} after it; from {@code start}. */
  private Ast bracket(Ast target, Token start) {
    advance();
    Ast from = null;
    Ast to = null;
    boolean slice = false;
    if (token.kind() == Token.Kind.COLON) {
      advance();
      slice = true;
      to = query();
    } else if (token.kind() != Token.Kind.RBRACKET) {
      from = query();
      if (token.kind() == Token.Kind.COLON) {
        advance();
        slice = true;
        if (token.kind() != Token.Kind.RBRACKET) {
          to = query();
        }
      }
    }
    int end = expect(Token.Kind.RBRACKET).end();
    boolean optional = token.kind() == Token.Kind.QUESTION;
    if (optional) {
      end = advance().end();
    }
    Source source = new Source(start.start(), end);
    if (slice) {
      return new Ast.Slice(source, target, from, to, optional);
    }
    return from == null ? new Ast.Each(source, target, optional) : new Ast.Index(source, target, from, optional);
  }

  /**
   * A leading dot: the identity, or {@code ."s"}; jq's {@code '.' error} when a token that cannot follow a term comes
   * next, and {@code '.' IDENT error} after a name, which both recover with jq's message over the dot and the error.
   */
  private Ast dot() {
    Token dot = advance();
    boolean outer = enter(true);
    try {
      switch (token.kind()) {
        case STRING_START, FORMAT -> {
          Ast.Str name = string();
          recovers = outer;
          boolean optional = token.kind() == Token.Kind.QUESTION;
          int end = optional ? advance().end() : name.source().end();
          return new Ast.StringField(new Source(dot.start(), end), null, name, optional);
        }
        case IDENT -> {
          advance();
          throw syntaxError();
        }
        default -> {
          if (NO_TERM_AFTER.contains(token.kind()) || token.kind() == Token.Kind.KEYWORD && !KEYWORDS_AFTER_A_TERM.contains(word(token))) {
            throw syntaxError();
          }
          recovers = outer;
          return new Ast.Dot(source(dot));
        }
      }
    } catch (Unwind u) {
      recovers = outer;
      fail(TRY_FIELD, dot.start(), u.end);
      if (u.drop) {
        discard();
      }
      return new Ast.Dot(new Source(dot.start(), u.end));
    }
  }

  /**
   * {@code {pair, ...}}, a comma after the last pair allowed. An error recovers as jq's {@code error ':' DictExpr}: the
   * tokens up to a colon are dropped, the value after it parsed, then jq's message over the error. The error starts at
   * the pair that the error is in, or at the first pair when it comes after a whole pair, as jq's pairs then reduce back
   * to the brace.
   */
  private Ast object() {
    Token open = advance();
    boolean outer = enter(true);
    List<Ast.Pair> pairs = new ArrayList<>();
    int first = token.start();
    int start = first;
    boolean recovering = false;
    int errorEnd = 0;
    for (;;) {
      try {
        if (recovering) {
          pairs.add(keyExpression(start, errorEnd));
          recovering = false;
        } else if (token.kind() == Token.Kind.RBRACE) {
          break;
        } else {
          pairs.add(pair());
        }
        if (token.kind() == Token.Kind.COMMA) {
          advance();
          start = token.start();
        } else if (token.kind() != Token.Kind.RBRACE) {
          start = first;
          throw syntaxError();
        }
      } catch (Unwind u) {
        if (u.drop) {
          discard();
        }
        recovering = true;
        errorEnd = u.end;
      }
    }
    Token close = advance();
    recovers = outer;
    return new Ast.ObjectCons(new Source(open.start(), close.end()), List.copyOf(pairs));
  }

  /** jq's {@code error ':' DictExpr} after an error over {@code [start, end)}: its message once the value is parsed. */
  private Ast.Pair keyExpression(int start, int end) {
    int errorEnd = end;
    while (token.kind() != Token.Kind.COLON) {
      if (token.kind() == Token.Kind.END) {
        throw errors();
      }
      errorEnd = token.end();
      discard();
    }
    advance();
    Ast value = dictExpr();
    fail(MAY_NEED_PARENTHESES, start, errorEnd);
    return new Ast.Pair(new Source(start, value.source().end()), null, value);
  }

  /** {@code key: value} or a key alone: a name or keyword, {@code $name}, {@code $__loc__} alone, a string, or {@code (query)}. */
  private Ast.Pair pair() {
    Token first = token;
    Ast.Key key;
    switch (token.kind()) {
      case IDENT, KEYWORD -> key = new Ast.NameKey(source(first), word(advance()));
      case BINDING -> key = new Ast.VarKey(source(first), variable(advance()));
      case LOC -> {
        advance();
        return new Ast.Pair(source(first), new Ast.LocKey(source(first)), null);
      }
      case STRING_START, FORMAT -> {
        Ast.Str string = string();
        key = new Ast.StringKey(string.source(), string);
      }
      case LPAREN -> {
        advance();
        Ast query = query();
        Token close = expect(Token.Kind.RPAREN);
        if (token.kind() != Token.Kind.COLON) {
          throw syntaxError();
        }
        key = new Ast.QueryKey(new Source(first.start(), close.end()), query);
      }
      default -> throw syntaxError();
    }
    if (token.kind() != Token.Kind.COLON) {
      return new Ast.Pair(key.source(), key, null);
    }
    advance();
    Ast value = dictExpr();
    return new Ast.Pair(new Source(first.start(), value.source().end()), key, value);
  }

  /** An object value, jq's DictExpr: expressions joined by {@code |}, which nests right; no comma or prefix form. */
  private Ast dictExpr() {
    Ast left = expr(0);
    if (token.kind() != Token.Kind.PIPE) {
      return left;
    }
    advance();
    Ast right = dictExpr();
    return new Ast.Pipe(span(left, right), left, right);
  }

  /** {@code pattern (?// pattern)*}. */
  private List<Ast.Pattern> patterns() {
    List<Ast.Pattern> patterns = new ArrayList<>();
    patterns.add(pattern());
    while (token.kind() == Token.Kind.ALTERNATION) {
      advance();
      patterns.add(pattern());
    }
    return List.copyOf(patterns);
  }

  /** {@code $name}, {@code [pattern, ...]} or {@code {entry, ...}}. */
  private Ast.Pattern pattern() {
    Token open = token;
    switch (token.kind()) {
      case BINDING -> {
        return new Ast.VarPattern(source(open), variable(advance()));
      }
      case LBRACKET -> {
        advance();
        List<Ast.Pattern> elements = new ArrayList<>();
        elements.add(pattern());
        while (token.kind() == Token.Kind.COMMA) {
          advance();
          elements.add(pattern());
        }
        Token close = expect(Token.Kind.RBRACKET);
        return new Ast.ArrayPattern(new Source(open.start(), close.end()), List.copyOf(elements));
      }
      case LBRACE -> {
        advance();
        // jq's error ':' Pattern of an object pattern: not built.
        boolean outer = enter(false);
        List<Ast.ObjPat> entries = new ArrayList<>();
        entries.add(objPat());
        while (token.kind() == Token.Kind.COMMA) {
          advance();
          entries.add(objPat());
        }
        Token close = expect(Token.Kind.RBRACE);
        recovers = outer;
        return new Ast.ObjectPattern(new Source(open.start(), close.end()), List.copyOf(entries));
      }
      default -> throw syntaxError();
    }
  }

  /** {@code $name}, or a key then {@code : pattern}: {@code $name}, a name or keyword, a string, or {@code (query)}. */
  private Ast.ObjPat objPat() {
    Token first = token;
    Ast.Key key;
    switch (token.kind()) {
      case BINDING -> {
        key = new Ast.VarKey(source(first), variable(advance()));
        if (token.kind() != Token.Kind.COLON) {
          return new Ast.ObjPat(source(first), key, null);
        }
      }
      case IDENT, KEYWORD -> key = new Ast.NameKey(source(first), word(advance()));
      case STRING_START, FORMAT -> {
        Ast.Str string = string();
        key = new Ast.StringKey(string.source(), string);
      }
      case LPAREN -> {
        advance();
        Ast query = query();
        Token close = expect(Token.Kind.RPAREN);
        key = new Ast.QueryKey(new Source(first.start(), close.end()), query);
      }
      default -> throw syntaxError();
    }
    expect(Token.Kind.COLON);
    Ast.Pattern value = pattern();
    return new Ast.ObjPat(new Source(first.start(), value.source().end()), key, value);
  }

  /**
   * A string literal, a FORMAT before it optional: its plain bytes and its escape runs' values joined into text parts
   * between its interpolations. A bad escape run adds nothing, as jq gives it no value; its block was added when read.
   */
  private Ast.Str string() {
    Token first = token;
    String format = null;
    if (token.kind() == Token.Kind.FORMAT) {
      format = word(advance()).substring(1);
    }
    expect(Token.Kind.STRING_START);
    List<Ast.Part> parts = new ArrayList<>();
    ByteArrayOutputStream value = new ByteArrayOutputStream();
    int textStart = -1;
    int textEnd = -1;
    for (;;) {
      Token part = token;
      switch (part.kind()) {
        case TEXT -> value.write(b, part.start(), part.end() - part.start());
        case ESCAPES -> value.writeBytes(JsonStrings.decode(b, part.start(), part.end()));
        case INVALID_ESCAPES -> {
          // jq's yylex gives the run no value and the parse goes on; the block was added when the token was read.
        }
        case INTERP_START, STRING_END -> {
          if (textStart >= 0) {
            parts.add(new Ast.Text(new Source(textStart, textEnd), value.toByteArray()));
            value.reset();
            textStart = -1;
          }
          advance();
          if (part.kind() == Token.Kind.STRING_END) {
            return new Ast.Str(new Source(first.start(), part.end()), format, List.copyOf(parts));
          }
          Ast query = query();
          Token close = expect(Token.Kind.INTERP_END);
          parts.add(new Ast.Interp(new Source(part.start(), close.end()), query));
          continue;
        }
        default -> throw syntaxError();
      }
      if (textStart < 0) {
        textStart = part.start();
      }
      textEnd = part.end();
      advance();
    }
  }

  /** Shifts the current token, never the END: Bison's shift, which counts toward the end of an error's recovery. */
  private Token advance() {
    if (errorStatus > 0) {
      errorStatus--;
    }
    return discard();
  }

  /** Moves to the next token; jq's {@code yylex} adds a bad escape run's block as it reads the run. */
  private Token discard() {
    Token t = token;
    token = tokens.get(++at);
    if (token.kind() == Token.Kind.INVALID_ESCAPES) {
      add(lexicalBlocks.get(nextLexicalBlock++), token.start());
    }
    return t;
  }

  private Token expect(Token.Kind kind) {
    if (token.kind() != kind) {
      throw syntaxError();
    }
    return advance();
  }

  private Token expectKeyword(String word) {
    if (!isKeyword(word)) {
      throw syntaxError();
    }
    return advance();
  }

  private boolean isKeyword(String word) {
    return token.kind() == Token.Kind.KEYWORD && word.equals(word(token));
  }

  /**
   * The current token is one the grammar rejects, as Bison's {@code yyerrlab}: reported unless an earlier error's
   * recovery is in progress; when that recovery has just failed on it, it is to be dropped, and at the end of the text
   * the parse ends. The error then goes to the innermost error state: an Unwind to it when it recovers, which drops the
   * token once its own reduction is done, as Bison reads the next one only then; else the errors so far.
   */
  private RuntimeException syntaxError() {
    boolean drop = errorStatus == 3;
    if (errorStatus == 0) {
      String name = token.kind() == Token.Kind.KEYWORD ? word(token) : token.kind().jqName;
      fail("syntax error, unexpected " + name, token.start(), token.end());
    } else if (drop && token.kind() == Token.Kind.END) {
      return errors();
    }
    errorStatus = 3;
    return recovers ? new Unwind(token.end(), drop) : errors();
  }

  /** Enters an error state that recovers or ends the parse; the caller restores the outer state it returns. */
  private boolean enter(boolean recovering) {
    boolean outer = recovers;
    recovers = recovering;
    return outer;
  }

  /** Adds jq's error block of {@code message} over {@code [start, end)}. */
  private void fail(String message, int start, int end) {
    add(text.block(message, start, end), start);
  }

  private void add(String block, int start) {
    if (blocks.isEmpty()) {
      firstLine = text.line(start);
      firstColumn = text.column(start);
    }
    blocks.add(block);
  }

  private JqCompileException errors() {
    return new JqCompileException(blocks, firstLine, firstColumn);
  }

  /** A token's bytes as text; the lexer took every name, keyword and number from ASCII ranges. */
  private String word(Token t) {
    return ascii(t.start(), t.end());
  }

  /** The name of a BINDING, without its {@code $}. */
  private String variable(Token t) {
    return ascii(t.start() + 1, t.end());
  }

  private String ascii(int start, int end) {
    return new String(b, start, end - start, US_ASCII);
  }

  private static Source source(Token t) {
    return new Source(t.start(), t.end());
  }

  private static Source span(Ast first, Ast last) {
    return new Source(first.source().start(), last.source().end());
  }

  /**
   * Bison's pop to the innermost error state that recovers: {@code end} is the error's end so far, and {@code drop}
   * whether the current token is to be dropped.
   */
  private static final class Unwind extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    final int end;
    final boolean drop;

    Unwind(int end, boolean drop) {
      super(null, null, false, false);
      this.end = end;
      this.drop = drop;
    }
  }
}
