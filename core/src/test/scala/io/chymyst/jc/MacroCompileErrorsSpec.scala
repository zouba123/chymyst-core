package io.chymyst.jc

import io.chymyst.jc.Core.ReactionBody
import utest._
import utest.framework.{Test, Tree}

import scala.concurrent.duration._

object MacroCompileErrorsSpec extends TestSuite {
  val tests: Tree[Test] = this {
    val a = m[Int]
    val c = m[Unit]
    val f = b[Int, Int]
    val x = 2

    assert(a.name == "a")
    assert(c.name == "c")
    assert(f.name == "f")
    assert(x == 2)

    "fail to compile molecules with non-unit types emitted as a()" - {

      * - {
        compileError(
          "val x = a()"
        ).check(
          """
            |          "val x = a()"
            |                    ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val x = f()"
        ).check(
          """
            |          "val x = f()"
            |                    ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val x = f.timeout()(1 second)"
        ).check(
          """
            |          "val x = f.timeout()(1 second)"
            |                              ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val r = go { case f(_, r) => r() } "
        ).check(
          """
            |          "val r = go { case f(_, r) => r() } "
            |                                         ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val r = go { case f(_, r) => r.checkTimeout() } "
        ).check(
          """
            |          "val r = go { case f(_, r) => r.checkTimeout() } "
            |                                                      ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
    }

    "fail to compile a reaction with empty static clause" - {
      compileError(
        "val r = go { case _ => }"
      ).check(
        """
          |        "val r = go { case _ => }"
          |                    ^
          |""".stripMargin, "Static reaction must emit some output molecules")
    }

    "fail to compile a guard that replies to molecules" - {
      * - {
        compileError(
          "val r = go { case f(_, r) if { r(1); x > 0 } => }"
        ).check(
          """
            |          "val r = go { case f(_, r) if { r(1); x > 0 } => }"
            |                      ^
            |""".stripMargin, "Input guard must not perform any reply actions (r)")
      }
      * - {
        compileError(
          "val r = go { case f(_, r) if r.checkTimeout(1) && x > 0 => }"
        ).check(
          """
            |          "val r = go { case f(_, r) if r.checkTimeout(1) && x > 0 => }"
            |                      ^
            |""".stripMargin, "Input guard must not perform any reply actions (r)")

      }
    }

    "fail to compile a guard that emits molecules" - {
      * - {
        compileError(
          "val r = go { case f(_, r) if f(1) > 0 => r(1) }"
        ).check(
          """
            |          "val r = go { case f(_, r) if f(1) > 0 => r(1) }"
            |                      ^
            |""".stripMargin, "Input guard must not emit any output molecules (f)")
      }

      * - {
        compileError(
          "val r = go { case f(_, r) if f.timeout(1)(1.second).nonEmpty => r(1) }"
        ).check(
          """
            |          "val r = go { case f(_, r) if f.timeout(1)(1.second).nonEmpty => r(1) }"
            |                      ^
            |""".stripMargin, "Input guard must not emit any output molecules (f)")
      }
    }

    "fail to compile a reaction with two case clauses" - {
      * - {
        compileError(
          "val r = go { case a(x) =>; case c(_) => }"
        ).check(
          """
            |          "val r = go { case a(x) =>; case c(_) => }"
            |                      ^
            |""".stripMargin, "Reactions must contain only one `case` clause")
      }
      * - {
        compileError(
          """val result =
go {
case a(x) => c()
case c(_) + a(y) => c()
}""").check(
          """
            |go {
            |   ^
            |""".stripMargin, "Reactions must contain only one `case` clause")
      }
    }

    "fail to compile a reaction that is not defined inline" - {
      val body: ReactionBody = {
        case _ => c()
      }
      assert(body.isInstanceOf[ReactionBody])
      compileError(
        "val r = go(body)"
      ).check(
        """
          |        "val r = go(body)"
          |                   ^
          |""".stripMargin, "No `case` clauses found: Reactions must be defined inline with the `go { case ... => ... }` syntax")
    }

    "fail to compile reactions with unconditional livelock when all matchers are trivial" - {
      val a = m[(Int, Int)]
      val bb = m[Int]
      val bbb = m[Int]

      assert(a.isInstanceOf[M[(Int, Int)]])
      assert(bb.isInstanceOf[M[Int]])
      assert(bbb.isInstanceOf[M[Int]])

      * - {
        compileError(
          "val r = go { case a((x,y)) => a((1,1)) }"
        ).check(
          """
            |          "val r = go { case a((x,y)) => a((1,1)) }"
            |                      ^
          """.stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (a)")
      }
      * - {
        compileError(
          "val r = go { case a((_,x)) => a((x,x)) }"
        ).check(
          """
            |          "val r = go { case a((_,x)) => a((x,x)) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (a)")
      }
      * - {
        val r = go { case a((1, _)) => a((1, 1)) }
        assert(r.isInstanceOf[Reaction])
      } // cannot detect unconditional livelock here at compile time, since we can't evaluate the binder yet
      * - {
        val r = go { case bb(y) if y > 0 => bb(1) }
        assert(r.isInstanceOf[Reaction])
      } // no unconditional livelock due to guard
      * - {
        val r = go { case bb(y) => if (y > 0) bb(1) }
        assert(r.isInstanceOf[Reaction])
      } // no unconditional livelock due to `if` in reaction
      * - {
        val r = go { case bb(y) => if (y > 0) bbb(1) else bb(2) }
        assert(r.isInstanceOf[Reaction])
      } // no unconditional livelock due to `if` in reaction
      * - {
        compileError(
          "val r = go { case bb(x) =>  if (x > 0) bb(1) else bb(2) }"
        ).check(
          """
            |          "val r = go { case bb(x) =>  if (x > 0) bb(1) else bb(2) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bb)")
      } // unconditional livelock due to shrinkage of `if` in reaction
      * - {
        val r = go { case bbb(1) => bbb(2) }
        assert(r.isInstanceOf[Reaction])
        // no livelock since constant values are different
      }
      * - {
        compileError(
          "val r = go { case bb(x) => bb(1) }"
        ).check(
          """
            |          "val r = go { case bb(x) => bb(1) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bb)")
      } // unconditional livelock
      * - {
        compileError(
          "val r = go { case a(_) => a((1,1)) }" // ignore warning "class M expects 2 patterns to hold"
        ).check(
          """
            |          "val r = go { case a(_) => a((1,1)) }" // ignore warning "class M expects 2 patterns to hold"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (a)")
      }
      // unconditional livelock
      * - {
        compileError(
          "val r = go { case bbb(_) => bbb(0) }"
        ).check(
          """
            |          "val r = go { case bbb(_) => bbb(0) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bbb)")
      }
      // unconditional livelock
      * - {
        compileError(
          "val r = go { case bbb(x) => bbb(x + 1) + bb(x) }"
        ).check(
          """
            |          "val r = go { case bbb(x) => bbb(x + 1) + bb(x) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bbb)")
      }
      * - {
        compileError(
          "val r = go { case bbb(x) + bb(y) => bbb(x + 1) + bb(x) + bb(y + 1) }"
        ).check(
          """
            |          "val r = go { case bbb(x) + bb(y) => bbb(x + 1) + bb(x) + bb(y + 1) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bbb, bb)")
      }
    }

    "fail to compile reactions with incorrect pattern matching" - {
      val a = b[Unit, Unit]
      val c = b[Unit, Boolean]
      val e = m[Unit]

      assert(a.isInstanceOf[B[Unit, Unit]])
      assert(c.isInstanceOf[B[Unit, Boolean]])
      assert(e.isInstanceOf[M[Unit]])

      // Note: these tests will produce several warnings "expects 2 patterns to hold but crushing into 2-tuple to fit single pattern".
      // However, it is precisely this crushing that we are testing here, that actually should not compile with our `go` macro.
      // So, these warnings cannot be removed and should be ignored.
      * - {
        compileError(
          "val r = go { case e() => }" // ignore warning "non-variable type argument"
        ).check(
          """
            |          "val r = go { case e() => }" // ignore warning "non-variable type argument"
            |                             ^
            |""".stripMargin, "not enough patterns for class M offering Unit: expected 1, found 0")
      }
      * - {
        compileError(
          "val r = go { case e(_,_) => }" // ignore warning "non-variable type argument"
        ).check(
          """
            |          "val r = go { case e(_,_) => }" // ignore warning "non-variable type argument"
            |                             ^
            |""".stripMargin, "too many patterns for class M offering Unit: expected 1, found 2")
      }
      * - {
        compileError(
          "val r = go { case e(_,_,_) => }" // ignore warning "non-variable type argument"
        ).check(
          """
            |          "val r = go { case e(_,_,_) => }" // ignore warning "non-variable type argument"
            |                             ^
            |""".stripMargin, "too many patterns for class M offering Unit: expected 1, found 3")
      }
      //    "val r = go { case a() => }" shouldNot compile // no pattern variable for reply in "a"
      * - {
        compileError(
          "val r = go { case a() => }" // ignore warning "non-variable type argument"
        ).check(
          """
            |          "val r = go { case a() => }" // ignore warning "non-variable type argument"
            |                             ^
            |""".stripMargin, "not enough patterns for class B offering (Unit, io.chymyst.jc.ReplyValue[Unit,Unit]): expected 2, found 0")
      }
      //    "val r = go { case a(_) => }" shouldNot compile // no pattern variable for reply in "a"
      * - {
        compileError(
          "val r = go { case a(_) => }" // ignore warning "class B expects 2 patterns"
        ).check(
          """
            |          "val r = go { case a(_) => }" // ignore warning "class B expects 2 patterns"
            |                      ^
            |""".stripMargin, "Blocking input molecules must contain a pattern that matches a reply emitter with a simple variable (molecule a)")
      }
      //    "val r = go { case a(_, _) => }" shouldNot compile // no pattern variable for reply in "a"
      * - {
        compileError(
          "val r = go { case a(_, _) => }"
        ).check(
          """
            |          "val r = go { case a(_, _) => }"
            |                      ^
            |""".stripMargin, "Blocking input molecules must contain a pattern that matches a reply emitter with a simple variable (molecule a)")
      }
      //    "val r = go { case a(_, _, _) => }" shouldNot compile // no pattern variable for reply in "a"
      * - {
        compileError(
          "val r = go { case a(_, _, _) => }" // ignore warning "non-variable type argument"
        ).check(
          """
            |          "val r = go { case a(_, _, _) => }" // ignore warning "non-variable type argument"
            |                             ^
            |""".stripMargin, "too many patterns for class B offering (Unit, io.chymyst.jc.ReplyValue[Unit,Unit]): expected 2, found 3")
      }
      //    "val r = go { case a(_, r) => }" shouldNot compile // no reply is performed with r
      * - {
        compileError(
          "val r = go { case a(_, r) => }"
        ).check(
          """
            |          "val r = go { case a(_, r) => }"
            |                      ^
            |""".stripMargin, "Blocking molecules must receive a reply but no unconditional reply found for (molecule r)")
      }
      //    "val r = go { case a(_, r) + a(_) + c(_) => r()  }" shouldNot compile // invalid patterns for "a" and "c"
      * - {
        compileError(
          "val r = go { case a(_, r) + a(_) + c(_) => r()  }" // ignore warning "class B expects 2 patterns"
        ).check(
          """
            |          "val r = go { case a(_, r) + a(_) + c(_) => r()  }" // ignore warning "class B expects 2 patterns"
            |                      ^
            |""".stripMargin, "Blocking input molecules must contain a pattern that matches a reply emitter with a simple variable (molecule a, molecule c)")
      }
      //    "val r = go { case a(_, r) + a(_) + c(_) => r(); r() }" shouldNot compile // two replies are performed with r, and invalid patterns for "a" and "c"
      * - {
        compileError(
          "val r = go { case a(_, r) + a(_) + c(_) => r(); r() }" // ignore warning "class B expects 2 patterns"
        ).check(
          """
            |          "val r = go { case a(_, r) + a(_) + c(_) => r(); r() }" // ignore warning "class B expects 2 patterns"
            |                      ^
            |""".stripMargin, "Blocking input molecules must contain a pattern that matches a reply emitter with a simple variable (molecule a, molecule c)")
      }
      //    "val r = go { case e(_) if true => c() }" should compile // input guard does not emit molecules
      * - {
        go { case e(_) if true => c() } // should compile without errors
      }
      //    "val r = go { case e(_) if c() => }" shouldNot compile // input guard emits molecules
      * - {
        compileError(
          "val r = go { case e(_) if c() => }"
        ).check(
          """
            |          "val r = go { case e(_) if c() => }"
            |                      ^
            |""".stripMargin, "Input guard must not emit any output molecules (c)")
      }
      //    "val r = go { case a(_,r) if r() => }" shouldNot compile // input guard performs reply actions
      * - {
        compileError(
          "val r = go { case a(_,r) if r.checkTimeout() => }"
        ).check(
          """
            |          "val r = go { case a(_,r) if r.checkTimeout() => }"
            |                      ^
            |""".stripMargin, "Input guard must not perform any reply actions (r)")
      }
      //    "val r = go { case e(_) => { case e(_) => } }" shouldNot compile // reaction body matches on input molecules
      * - {
        compileError(
          "val r = go { case e(_) => { case e(_) => }: ReactionBody }"
        ).check(
          """
            |          "val r = go { case e(_) => { case e(_) => }: ReactionBody }"
            |                      ^
            |""".stripMargin, "Reaction body must not contain a pattern that matches on molecules (e)")
      }
      * - {
        compileError(
          "val r = go { case e(_) if (null match { case e(_) => true }) => }"
        ).check(
          """
            |          "val r = go { case e(_) if (null match { case e(_) => true }) => }"
            |                      ^
            |""".stripMargin, "Input guard must not contain a pattern that matches on additional input molecules (e)")
      }
    }

    "fail to compile a reaction with regrouped inputs" - {
      val a = m[Unit]
      assert(a.isInstanceOf[M[Unit]])

      //      "val r = go { case a(_) + (a(_) + a(_)) => }" shouldNot compile
      * - {
        compileError(
          "val r = go { case a(_) + (a(_) + a(_)) => }"
        ).check(
          """
            |          "val r = go { case a(_) + (a(_) + a(_)) => }"
            |                      ^
            |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
      }
      //      "val r = go { case a(_) + (a(_) + a(_)) + a(_) => }" shouldNot compile
      * - {
        compileError(
          "val r = go { case a(_) + (a(_) + a(_)) + a(_) => }"
        ).check(
          """
            |          "val r = go { case a(_) + (a(_) + a(_)) + a(_) => }"
            |                      ^
            |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
      }
      //      "val r = go { case (a(_) + a(_)) + a(_) + a(_) => }" should compile
      * - {
        go { case (a(_) + a(_)) + a(_) + a(_) => }
      }
    }

    "miscellaneous compile-time errors" - {

      "fail to compile reactions with no input molecules" - {
        val bb = m[Int]
        val bbb = m[Int]

        assert(bb.isInstanceOf[M[Int]])
        assert(bbb.isInstanceOf[M[Int]])

        //      "val r = go { case _ => bb(0) }" should compile // declaration of a static molecule
        * - {
          go { case _ => bb(0) }
        }
        //      "val r = go { case x => x }" shouldNot compile // no input molecules
        * - {
          compileError(
            "val r = go { case x => x }"
          ).check(
            """
              |            "val r = go { case x => x }"
              |                        ^
              |""".stripMargin, "Reaction input must be `_` or must contain some input molecules, but is (x @ _)")
        }
        //      "val r = go { case x => bb(x.asInstanceOf[Int]) }" shouldNot compile // no input molecules
        * - {
          compileError(
            "val r = go { case x => bb(x.asInstanceOf[Int]) }"
          ).check(
            """
              |            "val r = go { case x => bb(x.asInstanceOf[Int]) }"
              |                        ^
              |""".stripMargin, "Reaction input must be `_` or must contain some input molecules, but is (x @ _)")
        }
      }

      "fail to compile a reaction with grouped pattern variables in inputs" - {
        val a = m[Unit]
        assert(a.name == "a")

        //      "val r = go { case a(_) + x@(a(_) + a(_)) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case a(_) + x@(a(_) + a(_)) => }"
          ).check(
            """
              |val r = go { case a(_) + x@(a(_) + a(_)) => }
              |                           ^
              |""".stripMargin, "'=>' expected but '@' found.")
        }
        //      "val r = go { case a(_) + (a(_) + a(_)) + x@a(_) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case a(_) + (a(_) + a(_)) + x@a(_) => }"
          ).check(
            """
              |val r = go { case a(_) + (a(_) + a(_)) + x@a(_) => }
              |                                           ^
              |""".stripMargin, "'=>' expected but '@' found.")
        }
        //      "val r = go { case x@a(_) + (a(_) + a(_)) + a(_) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case x@a(_) + (a(_) + a(_)) + a(_) => }"
          ).check(
            """
              |            "val r = go { case x@a(_) + (a(_) + a(_)) + a(_) => }"
              |                        ^
              |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
        }
        * - {
          compileError(
            "val r = go { case (x@a(_) + a(_)) + a(_) => }"
          ).check(
            """
              |            "val r = go { case (x@a(_) + a(_)) + a(_) => }"
              |                        ^
              |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
        }
        * - {
          compileError(
            "val r = go { case a(_) + (x@a(_) + a(_)) + a(_) => }"
          ).check(
            """
              |            "val r = go { case a(_) + (x@a(_) + a(_)) + a(_) => }"
              |                        ^
              |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
        }
        //      "val r = go { case x@(a(_) + a(_)) + a(_) + a(_) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case x@(a(_) + a(_)) + a(_) + a(_) => }"
          ).check(
            """
              |            "val r = go { case x@(a(_) + a(_)) + a(_) + a(_) => }"
              |                        ^
              |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
        }
        //      "val r = go { case x@a(_) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case x@a(_) => }"
          ).check(
            """
              |            "val r = go { case x@a(_) => }"
              |                        ^
              |""".stripMargin, "Reaction's input molecules must be grouped to the left in chemical notation, and have no @-pattern variables")
        }
      }

      "refuse reactions that match on other molecules in molecule input values" - {
        val a = m[Any]
        val c = m[Any]
        val f = b[Any, Any]
        assert(a.name == "a")
        assert(c.name == "c")
        assert(f.name == "f")

        go { case a(1) => a(a(1)) } // OK

        //      "val r = go { case a(a(1)) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case a(c(1)) => }"
          ).check(
            """
              |            "val r = go { case a(c(1)) => }"
              |                        ^
              |""".stripMargin, "Input molecules must not contain a pattern that uses other molecules inside molecule value patterns (c)")
        }
        //      "val r = go { case f(_, 123) => }" shouldNot compile
        * - {
          compileError(
            "val r = go { case f(_, 123) => }"
          ).check(
            """
              |            "val r = go { case f(_, 123) => }"
              |                                    ^
              |""".stripMargin, "type mismatch;\n found   : Int(123)\n required: io.chymyst.jc.ReplyValue[Any,Any]")
        }
        * - {
          compileError(
            "val r = go { case f(_, null) => }"
          ).check(
            """
              |            "val r = go { case f(_, null) => }"
              |                        ^
              |""".stripMargin, "Blocking input molecules must contain a pattern that matches a reply emitter with a simple variable (molecule f)")
        }
        //      "val r = go { case f(a(1), r) => r(1) }" shouldNot compile
        * - {
          compileError(
            "val r = go { case f(a(1), r) => r(1) }"
          ).check(
            """
              |            "val r = go { case f(a(1), r) => r(1) }"
              |                        ^
              |""".stripMargin, "Input molecules must not contain a pattern that uses other molecules inside molecule value patterns (a)")
        }
        //      "val r = go { case f(f(1, s), r) => r(1) }" shouldNot compile
        * - {
          compileError(
            "val r = go { case f(f(1, s), r) => r(1) }"
          ).check(
            """
              |            "val r = go { case f(f(1, s), r) => r(1) }"
              |                        ^
              |""".stripMargin, "Input molecules must not contain a pattern that uses other molecules inside molecule value patterns (f)")
        }
      }
    }

    // End of tests.
  }
}