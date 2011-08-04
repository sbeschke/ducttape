package ducttape.workflow

import collection._

import ducttape.hyperdag._
import ducttape.syntax.AbstractSyntaxTree._
import ducttape.syntax.FileFormatException
import ducttape.Types._

object Task {
  val NO_BRANCH_POINT = new BranchPoint("Baseline")
  val NO_BRANCH = new Branch("baseline", NO_BRANCH_POINT)

  // sort by branch *point* names to keep ordering consistent, then join branch names using dashes
  // and don't include our default branch "baseline"
  def realizationName(real: Map[String,Branch]) = {
    val branches = real.toSeq.sortBy(_._1).map(_._2)
    branches.size match {
      case 0 => NO_BRANCH // make sure we have at least baseline in the name
      case 1 => branches.head.name // keep baseline if it's the only (it may not be)
      case _ => branches.filter(_ != NO_BRANCH).map(_.name).mkString("-")
    }
  }
  //def realizationName(real: Seq[Branch]) = realizationName(branchesToMap(real))
  def branchesToMap(real: Seq[Branch]) = {
    val result = new mutable.HashMap[String,Branch]
    for(branch <- real) {
      result += branch.branchPoint.name -> branch
    }
    result
  }
}

// short for "realized task"
// we might shorten this to Task
class RealTask(val taskT: TaskTemplate,
               val activeBranches: Map[String,Branch], // TODO: Keep string branch point names?
               val inputVals: Seq[(Spec,Spec,TaskDef)], // (mySpec,srcSpec,srcTaskDef)
               val paramVals: Seq[(Spec,LiteralSpec,TaskDef)]) { // (mySpec,srcSpec,srcTaskDef)
  def name = taskT.name
  def realizationName = Task.realizationName(activeBranches)
  def taskDef = taskT.taskDef
  def comments = taskT.comments
  def inputs = taskT.inputs
  def outputs = taskT.outputs
  def params = taskT.params
  def commands = taskT.commands // TODO: This will no longer be valid once we add in-lines

  override def toString = name + "/" + activeBranches.values.mkString("-")
}

// TODO: fix these insane types for inputVals and paramVals
class TaskTemplate(val taskDef: TaskDef,
           val branchPoints: Seq[BranchPoint],
           val inputVals: Seq[(Spec,Map[Branch,(Spec,TaskDef)])], // (mySpec,srcSpec,srcTaskDef)
           val paramVals: Seq[(Spec,Map[Branch,(LiteralSpec,TaskDef)])] ) { // (mySpec,srcSpec,srcTaskDef)
  def name = taskDef.name
  def comments = taskDef.comments
  def inputs = taskDef.inputs
  def outputs = taskDef.outputs
  def params = taskDef.params
  def commands = taskDef.commands

  override def toString = name

  // realize this task by specifying one branch per branch point
  // activeBranches should contain only the hyperedges encountered up until this vertex
  // with the key being the branchPointNames
  def realize(activeBranches: Seq[Branch]): RealTask = {
    // TODO: Assert all of our branch points are satisfied
    // TODO: We could try this as a view.map() instead of just map() to only calculate these on demand...

    val activeBranchMap = Task.branchesToMap(activeBranches)

    // do a bit of sanity checking
    for(branchPoint <- branchPoints) {
      assert(activeBranchMap.contains(branchPoint.name),
             "Required branch point for this task '%s' not found in active branch points '%s'"
             .format(branchPoint.name, activeBranchMap.keys.mkString("-")))
    }

    // resolve the source spec/task for the selected branch
    def mapVals[T](values: Seq[(Spec,Map[Branch,(T,TaskDef)])]): Seq[(Spec,T,TaskDef)] = {
      values.map{ case (mySpec, branchMap) => mySpec.rval match {
        case BranchPointDef(branchPointName, _) => {
          val activeBranch: Branch = activeBranchMap(branchPointName)
          val(srcSpec: T, srcTaskDef) = branchMap(activeBranch)
          (mySpec, srcSpec, srcTaskDef)
        }
        case _ => { // not a branch point
          val(srcSpec: T, srcTaskDef) = branchMap.values.head
          (mySpec, srcSpec, srcTaskDef)
        }
      }}
    }

    val realInputVals = mapVals(inputVals)
    val realParamVals = mapVals(paramVals)

    new RealTask(this, activeBranchMap, realInputVals, realParamVals)
  }
}

// TODO: Add Option[BranchPointDef] for line no info
class BranchPoint(val name: String) {
  override def toString = name
}

// TODO: Branch manager to remove bi-directional dependency?
// TODO: Add Option[BranchPointDef] for line no info
class Branch(val name: String,
             val branchPoint: BranchPoint) {
  override def toString = name + "@" + branchPoint
}

object WorkflowBuilder {

  case class ResolveMode;
  case class InputMode extends ResolveMode;
  case class ParamMode extends ResolveMode;
  case class OutputMode extends ResolveMode;

  // the resolved Spec is guaranteed to be a literal for params
  private def resolveParam(taskDef: TaskDef,
                             map: Map[String,TaskDef],
                             spec: Spec)
  : (LiteralSpec, TaskDef) = {
    resolveVar(taskDef, map, spec, ParamMode()).asInstanceOf[(LiteralSpec,TaskDef)]
  }

  private def resolveVar(taskDef: TaskDef,
                         map: Map[String,TaskDef],
                         spec: Spec,
                         mode: ResolveMode)
  : (Spec, TaskDef) = {

    var curSpec: Spec = spec
    var src: TaskDef = taskDef
    while(true) {
      curSpec.rval match {
        case Literal(litValue) => {
          val litSpec = curSpec.asInstanceOf[LiteralSpec] // guaranteed to succeed
          return (litSpec, src)
        }
        case Variable(srcTaskName, srcOutName) => {
          map.get(srcTaskName) match {
            case Some(srcDef: TaskDef) => {
              // determine where to search when resolving this variable's parent spec
              val specSet = mode match {
                case InputMode() => srcDef.outputs
                case ParamMode() => srcDef.params
              }
              // now search for the parent spec
              specSet.find(outSpec => outSpec.name == srcOutName) match {
                case Some(srcSpec) => curSpec = srcSpec
                case None => {
                  // TODO: Line # of srcDef & spec
                  throw new FileFormatException(
                    "Output %s at source task %s for input %s at task %s not found".format(
                      srcOutName, srcTaskName, spec.name, taskDef.name))
                }
              }
              // assign after we've gotten a chance to print error messages
              src = srcDef
            }
            case None => {
              // TODO: Line #
              throw new FileFormatException(
                "Source task %s for input %s at task %s not found".format(
                  srcTaskName, spec.name, taskDef.name))
            }
          }
        }
        case BranchPointDef(name, specs: Seq[Spec]) => {
          throw new RuntimeException("Expected branches to be resolved by now")
        }
        case Unbound() => {
          mode match {
            case InputMode() => return (curSpec, src)
            case _ => throw new RuntimeException("Unsupported unbound variable: %s".format(curSpec.name))
          }
        }
      }
    }
    throw new Error("Unreachable")
  }

  // create dependency pointers based on workflow definition
  // TODO: This method has become morbidly obese -- break it out into several methods
  def build(wd: WorkflowDefinition): HyperWorkflow = {

    val defMap = new mutable.HashMap[String,TaskDef]
    for(t <- wd.tasks) {
      defMap += t.name -> t
    }

    // (task, parents) -- Option as None indicates that parent should be a phantom vertex
    // so as not to affect temporal ordering nor appear when walking the DAG
    // TODO: Fix this painful data structure into something more readable (use typedefs?)
    val parents = new mutable.HashMap[TaskTemplate, Map[Branch,mutable.Set[Option[TaskDef]]]] // TODO: Multimap
    val vertices = new mutable.HashMap[String,PackedVertex[TaskTemplate]]
    val dag = new MetaHyperDagBuilder[TaskTemplate,BranchPoint,Branch,Null](null,null,null)
    val branchPoints = new mutable.ArrayBuffer[BranchPoint]
    val branchPointsByTask = new mutable.HashMap[TaskDef,mutable.Set[BranchPoint]] // TODO: Multimap

    // first, create tasks, but don't link them in graph yet
    for(taskDef <- wd.tasks) {

      // TODO: Check for all inputs/outputs/param names being unique in this step

      if(vertices.contains(taskDef.name)) {
        // TODO: Line #'s
        throw new FileFormatException("Duplicate task name: %s".format(taskDef.name))
      }

      // define branch resolution as a function that takes some callback functions
      // since we use it for both parameter and input file resolution,
      // but it behaves slightly different for each (parameters don't
      // imply a temporal ordering between vertices)
      def resolveBranchPoint[SpecT](inSpec: Spec,
                        resolvedVars: mutable.ArrayBuffer[(Spec,Map[Branch,(SpecT,TaskDef)])],
                        recordParentsFunc: (Branch,Option[TaskDef]) => Unit,
                        resolveVarFunc: (TaskDef, Map[String,TaskDef], Spec) => (SpecT, TaskDef) ) = {
        inSpec.rval match {
          case BranchPointDef(branchPointName, branchSpecs: Seq[Spec]) => {

            // TODO: If a branch point is *REDECLARED* in a workflow
            // assert that it has exactly the same branches in all locations
            // else die
            // This must be done for the paramSpecs above as well

            val branchPoint = new BranchPoint(branchPointName)
            val branchMap = new mutable.HashMap[Branch, (SpecT,TaskDef)]
            for(branchSpec <- branchSpecs) {
              val branch = new Branch(branchSpec.name, branchPoint)
              val (srcSpec, srcTaskDef) = resolveVarFunc(taskDef, defMap, branchSpec)
              branchMap.put(branch, (srcSpec, srcTaskDef) )
              if(srcTaskDef != taskDef) { // don't create cycles
                recordParentsFunc(branch, Some(srcTaskDef))
              } else {
                recordParentsFunc(branch, None)
              }
            }

            // TODO: Rework this so that branch points can be associated with multiple tasks
            branchPoints += branchPoint
            branchPointsByTask.getOrElseUpdate(taskDef, {new mutable.HashSet}) += branchPoint
            resolvedVars.append( (inSpec, branchMap) )
          }
          case _ => {
            val (srcSpec, srcTaskDef) = resolveVarFunc(taskDef, defMap, inSpec)
            resolvedVars.append( (inSpec, Map(Task.NO_BRANCH -> (srcSpec, srcTaskDef)) ) )

            if(srcTaskDef != taskDef) { // don't create cycles
              recordParentsFunc(Task.NO_BRANCH, Some(srcTaskDef))
            } else {
              recordParentsFunc(Task.NO_BRANCH, None)
            }
            branchPoints += Task.NO_BRANCH_POINT
            branchPointsByTask.getOrElseUpdate(taskDef, {new mutable.HashSet}) += Task.NO_BRANCH_POINT
          }
        }
      }

      val parentsByBranch = new mutable.HashMap[Branch,mutable.Set[Option[TaskDef]]]

      // parameters are different than file dependencies in that they do not
      // add any temporal dependencies between tasks and therefore do not
      // add any edges in the MetaHyperDAG
      val paramVals = new mutable.ArrayBuffer[(Spec,Map[Branch,(LiteralSpec,TaskDef)])](taskDef.params.size)
      for(paramSpec: Spec <-taskDef.params) {
        def recordParentsFunc(branch: Branch, srcTaskDef: Option[TaskDef]) {
          // params have no effect on temporal ordering, but can affect derivation of branches
          // therefore, params are *always* rooted at phantom vertices, no matter what
         parentsByBranch.getOrElseUpdate(branch, {new mutable.HashSet}) += None
        }
        def resolveVarFunc(taskDef: TaskDef, defMap: Map[String,TaskDef], paramSpec: Spec)
          : (LiteralSpec, TaskDef) = {
            resolveParam(taskDef, defMap, paramSpec)
        }
        resolveBranchPoint(paramSpec, paramVals, recordParentsFunc, resolveVarFunc)
      }

      val inputVals = new mutable.ArrayBuffer[(Spec,Map[Branch,(Spec,TaskDef)])](taskDef.inputs.size)
      // TODO: Roll own multimap since scala's is a bit awkward
      for(inSpec: Spec <- taskDef.inputs) {
        def recordParentsFunc(branch: Branch, srcTaskDef: Option[TaskDef]) {
          // make a note of what edges we'll need to add later
          parentsByBranch.getOrElseUpdate(branch, {new mutable.HashSet}) += srcTaskDef
        }
        def resolveVarFunc(taskDef: TaskDef, defMap: Map[String,TaskDef], inSpec: Spec)
          : (Spec, TaskDef) = {
            resolveVar(taskDef, defMap, inSpec, InputMode())
        }
        resolveBranchPoint(inSpec, inputVals, recordParentsFunc, resolveVarFunc)
      }

      val task = new TaskTemplate(taskDef, branchPointsByTask(taskDef).toSeq, inputVals, paramVals)
      parents += task -> parentsByBranch
      vertices += task.name -> dag.addVertex(task)
    }

    // now link the tasks in the graph by adding (meta/hyper) edges
    for(v <- vertices.values) {
      val task: TaskTemplate = v.value

      // add one metaedge per branch point
      for(branchPoint <- branchPointsByTask(task.taskDef)) {
        // create a hyperedge list in the format expected by the HyperDAG API

        val hyperedges = new mutable.ArrayBuffer[(Branch, Seq[(Option[PackedVertex[TaskTemplate]],Null)])]
        for( (branch, parentTaskDefs) <- parents(task); if branchPoint == branch.branchPoint) {
          
          val edges = new mutable.ArrayBuffer[(Option[PackedVertex[TaskTemplate]],Null)]
          // parents are stored as Options so that we can use None to indicate phantom parent vertices
          for( parentTaskDefOpt <- parentTaskDefs) parentTaskDefOpt match {
            case Some(parentTaskDef) => {
              val parentVert = vertices(parentTaskDef.name)
              edges.append( (Some(parentVert), null) )
            }
            case None => {
              edges.append( (None, null) )
            }
          }
          hyperedges.append( (branch, edges) )
        }
        if(!hyperedges.isEmpty) {
          // NOTE: The meta edges are not necessarily phantom, but just have that option
          dag.addPhantomMetaEdge(branchPoint, hyperedges, v)
        }
      }
    }

    // TODO: For params, we can resolve these values *ahead*
    // of time, prior to scheduling (but keep relationship info around)
    new HyperWorkflow(dag.build)
  }
}