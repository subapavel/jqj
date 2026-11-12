package io.github.subapavel.jqj.internal.parse;

import java.util.List;

/**
 * A parsed filter (ADR-0032): jq's {@code TopLevel}, its module directive's metadata (null without one), its imports,
 * and either its top-level filter, definitions before it folded into it as {@link Ast.Def}s, or only definitions, with
 * a null main.
 */
public record Program(Ast module, List<Ast.Import> imports, List<Ast.FuncDef> defs, Ast main) {}
