package com.prisma.deploy.connector.mongo.database

import com.prisma.deploy.connector.mongo.impl.DeployMongoAction
import com.prisma.shared.models.Project
import org.mongodb.scala.model.IndexOptions
import org.mongodb.scala.model.Sorts.ascending
import org.mongodb.scala.{MongoNamespace, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object NoAction {
  val unit = {
    DeployMongoAction { database =>
      Future.successful(())
    }
  }
}

object MongoDeployDatabaseMutationBuilder {

  //Project
  def createClientDatabaseForProject = DeployMongoAction { database =>
    database.listCollectionNames().toFuture().map(_ => ())
  }

  //needs to add relation indexes as well
  def truncateProjectTables(project: Project) = DeployMongoAction { database =>
    val nonEmbeddedModels = project.models.filter(!_.isEmbedded)

    def dropTables: Future[List[Completed]] = Future.sequence(nonEmbeddedModels.map(model => database.getCollection(model.dbName).drop().toFuture()))

    def createTables(drops: List[Completed]): Future[List[Completed]] =
      Future.sequence(nonEmbeddedModels.map(model => database.createCollection(model.dbName).toFuture()))

    def createUniques(creates: List[Completed]): Future[List[List[Any]]] =
      Future.sequence(nonEmbeddedModels.map { model =>
        Future.sequence(model.scalarNonListFields.filter(_.isUnique).map(field => addUniqueConstraint(database, model.dbName, field.name)))
      })

    def createRelationIndexes(uniques: List[List[Any]]) =
      Future.sequence(project.relations.collect {
        case relation if relation.isInlineRelation =>
          relation.modelAField.relationIsInlinedInParent match {
            case true  => addRelationIndex(database, relation.modelAField.model.dbName, relation.modelAField.dbName)
            case false => addRelationIndex(database, relation.modelBField.model.dbName, relation.modelBField.dbName)
          }
      })

    for {
      drops: List[Completed]   <- dropTables
      creates: List[Completed] <- createTables(drops)
      uniques                  <- createUniques(creates)
      relations                <- createRelationIndexes(uniques)
    } yield ()
  }

  def deleteProjectDatabase = DeployMongoAction { database =>
    database.drop().toFuture().map(_ -> Unit)
  }

  //Collection
  def createCollection(collectionName: String) = DeployMongoAction { database =>
    database.createCollection(collectionName).toFuture().map(_ -> Unit)
  }

  def dropCollection(collectionName: String) = DeployMongoAction { database =>
    database.getCollection(collectionName).drop().toFuture().map(_ -> Unit)
  }

  def renameCollection(projectId: String, collectionName: String, newName: String) = DeployMongoAction { database =>
    database.getCollection(collectionName).renameCollection(MongoNamespace(projectId, newName)).toFuture().map(_ -> Unit)
  }

  //Fields
  def createField(collectionName: String, fieldName: String) = DeployMongoAction { database =>
    addUniqueConstraint(database, collectionName, fieldName)
  }

  def deleteField(collectionName: String, fieldName: String) = DeployMongoAction { database =>
    removeUniqueConstraint(database, collectionName, fieldName)
  }

  def updateField(name: String, newName: String) = NoAction.unit

  def addUniqueConstraint(database: MongoDatabase, collectionName: String, fieldName: String) = {
    val shortenedName = indexNameHelper(collectionName, fieldName, true)

    database
      .getCollection(collectionName)
      .createIndex(ascending(fieldName), IndexOptions().unique(true).sparse(true).name(shortenedName))
      .toFuture()
      .map(_ => ())
  }

  def removeUniqueConstraint(database: MongoDatabase, collectionName: String, fieldName: String) = {
    val shortenedName = indexNameHelper(collectionName, fieldName, true)

    database
      .getCollection(collectionName)
      .dropIndex(shortenedName)
      .toFuture()
      .map(_ => ())
  }

  def addRelationIndex(database: MongoDatabase, collectionName: String, fieldName: String) = {
    val shortenedName = indexNameHelper(collectionName, fieldName, false)

    database
      .getCollection(collectionName)
      .createIndex(ascending(fieldName), IndexOptions().name(shortenedName))
      .toFuture()
      .map(_ => ())
  }

  def removeRelationIndex(database: MongoDatabase, collectionName: String, fieldName: String) = {
    val shortenedName = indexNameHelper(collectionName, fieldName, false)

    database
      .getCollection(collectionName)
      .dropIndex(shortenedName)
      .toFuture()
      .map(_ => ())
  }

  def indexNameHelper(collectionName: String, fieldName: String, unique: Boolean): String = {
    val shortenedName = fieldName.substring(0, (125 - 25 - collectionName.length - 12).min(fieldName.length))

    unique match {
      case false => shortenedName + "_R"
      case true  => shortenedName + "_U"
    }
  }

}
