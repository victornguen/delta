/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.commands

// scalastyle:off import.ordering.noEmptyLine
import scala.util.control.NonFatal

import org.apache.spark.sql.delta.{DeletionVectorsTableFeature, DeltaConfigs, DeltaErrors, DeltaOperations, IcebergCompatBase, Snapshot}
import org.apache.spark.sql.delta.IcebergCompat.{getEnabledVersion, getForVersion}
import org.apache.spark.sql.delta.UniversalFormat.{icebergEnabled, ICEBERG_FORMAT}
import org.apache.spark.sql.delta.actions.{AddFile, Protocol}
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.logging.DeltaLogKeys
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.util.Utils.try_element_at

import org.apache.spark.internal.MDC
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.functions.col

/**
 * Helper trait for ReorgTableCommand to rewrite the table to be Iceberg compatible.
 */
trait ReorgTableForUpgradeUniformHelper extends DeltaLogging {

  private val rewriteCheckTable: Map[Int, Set[Int]] =
    Map(0 -> Set(2, 3), 1 -> Set(2, 3), 2 -> Set(2, 3), 3 -> Set(2, 3))

  /**
   * Check if the given pair of (old_version, new_version) should trigger a rewrite check.
   * NOTE: Actual rewrite only happens when not all addFiles has tags with newVersion.
   */
  private def shallCheckRewrite(oldVersion: Int, newVersion: Int): Boolean = {
    rewriteCheckTable.getOrElse(oldVersion, Set.empty[Int]).contains(newVersion)
  }

  /**
   * Helper function to rewrite the table. Implemented by Reorg Table Command.
   */
  def optimizeByReorg(sparkSession: SparkSession): Seq[Row]

  /**
   * Enable the new IcebergCompat on the table by updating table conf.
   */
  private def enableIcebergCompat(
      table: DeltaTableV2,
      currentCompatVersion: Option[Int],
      compatToEnable: IcebergCompatBase): Unit = {
    var newConf: Map[String, String] = Map(
      compatToEnable.config.key -> "true",
      DeltaConfigs.COLUMN_MAPPING_MODE.key -> "name") ++
      currentCompatVersion.map(getForVersion(_).config.key -> "false") // Disable old IcebergCompat

    if (compatToEnable.incompatibleTableFeatures.contains(DeletionVectorsTableFeature)) {
      newConf += DeltaConfigs.ENABLE_DELETION_VECTORS_CREATION.key -> "false"
    }

    val alterConfTxn = table.startTransaction()

    if (alterConfTxn.protocol.minWriterVersion < 7) {
      newConf += Protocol.MIN_WRITER_VERSION_PROP -> "7"
    }
    if (alterConfTxn.protocol.minReaderVersion < 3) {
      newConf += Protocol.MIN_READER_VERSION_PROP -> "3"
    }

    val metadata = alterConfTxn.metadata
    val newMetadata = metadata.copy(
      description = metadata.description,
      configuration = metadata.configuration ++ newConf)
    alterConfTxn.updateMetadata(newMetadata)
    alterConfTxn.commit(
      Nil,
      DeltaOperations.UpgradeUniformProperties(newConf)
    )
  }

  /**
   * Helper function to get the num of addFiles as well as
   * num of addFiles with ICEBERG_COMPAT_VERSION tag.
   * @param icebergCompatVersion target iceberg compat version
   * @param snapshot current snapshot
   * @return (NumOfAddFiles, NumOfAddFilesWithIcebergCompatTag)
   */
  private def getNumOfAddFiles(
      icebergCompatVersion: Int,
      table: DeltaTableV2,
      snapshot: Snapshot): (Long, Long) = {
    val numOfAddFilesWithTag = snapshot.allFiles
      .select("tags")
      .where(try_element_at(col("tags"), AddFile.Tags.ICEBERG_COMPAT_VERSION.name)
        === icebergCompatVersion.toString)
      .count()
    val numOfAddFiles = snapshot.numOfFiles
    logInfo(log"For table ${MDC(DeltaLogKeys.TABLE_NAME, table.tableIdentifier)} " +
      log"at version ${MDC(DeltaLogKeys.VERSION, snapshot.version)}, there are " +
      log"${MDC(DeltaLogKeys.NUM_FILES, numOfAddFiles)} addFiles, and " +
      log"${MDC(DeltaLogKeys.NUM_FILES2, numOfAddFilesWithTag)} addFiles with " +
      log"ICEBERG_COMPAT_VERSION=${MDC(DeltaLogKeys.VERSION2, icebergCompatVersion.toLong)} tag.")
    (numOfAddFiles, numOfAddFilesWithTag)
  }

  /**
   * Helper function to rewrite the table data files in Iceberg compatible way.
   * This method would do following things:
   * 1. Update the table properties to enable the target iceberg compat version and disable the
   *    existing iceberg compat version.
   * 2. If target iceberg compat version require rewriting and not all addFiles has
   *    ICEBERG_COMPAT_VERSION=version tag, rewrite the table data files to be iceberg compatible
   *    and adding tag to all addFiles.
   * 3. If universal format not enabled, alter the table properties to enable
   *    universalFormat = Iceberg.
   *
   * * There are six possible write combinations:
   * | CurrentIcebergCompatVersion | TargetIcebergCompatVersion | Required steps|
   * | --------------------------- | -------------------------- | ------------- |
   * |      None                   |         1                  |   1, 3        |
   * |      None                   |         2+                 |   1, 2, 3     |
   * |      1                      |         1                  |   3           |
   * |      1                      |         2+                 |   1, 2, 3     |
   * |      2+                     |         1                  |   1, 3        |
   * |      2+                     |         2+                 |   2, 3        |
   */
  private def doRewrite(
      target: DeltaTableV2,
      sparkSession: SparkSession,
      targetIcebergCompatVersion: Int): Seq[Row] = {

    val snapshot = target.update()
    val currIcebergCompatVersionOpt = getEnabledVersion(snapshot.metadata)
    if (targetIcebergCompatVersion >= 3) {
      throw DeltaErrors.icebergCompatVersionNotSupportedException(targetIcebergCompatVersion, 2)
    }
    val targetIcebergCompatObject = getForVersion(targetIcebergCompatVersion)
    val mayNeedRewrite = shallCheckRewrite(
      currIcebergCompatVersionOpt.getOrElse(0), targetIcebergCompatVersion)

    // Step 1: Update the table properties to enable the target iceberg compat version
    val didUpdateIcebergCompatVersion =
      if (!currIcebergCompatVersionOpt.contains(targetIcebergCompatVersion)) {
        enableIcebergCompat(target, currIcebergCompatVersionOpt, targetIcebergCompatObject)
        logInfo(log"Update table ${MDC(DeltaLogKeys.TABLE_NAME, target.tableIdentifier)} " +
          log"to iceberg compat version = " +
          log"${MDC(DeltaLogKeys.VERSION, targetIcebergCompatVersion)} successfully.")
        true
      } else {
        false
      }

    // Step 2: Rewrite the table data files to be Iceberg compatible.
    val (numOfAddFilesBefore, numOfAddFilesWithTagBefore) = getNumOfAddFiles(
      targetIcebergCompatVersion, target, snapshot)
    val allAddFilesHaveTag = numOfAddFilesWithTagBefore == numOfAddFilesBefore
    // The table needs to be rewritten if:
    //   1. The target iceberg compat version requires rewrite.
    //   2. Not all addFile have ICEBERG_COMPAT_VERSION=targetVersion tag
    val (metricsOpt, didRewrite) = if (mayNeedRewrite && !allAddFilesHaveTag) {
      logInfo(log"Reorg Table ${MDC(DeltaLogKeys.TABLE_NAME, target.tableIdentifier)} to " +
        log"iceberg compat version = ${MDC(DeltaLogKeys.VERSION, targetIcebergCompatVersion)} " +
        log"need rewrite data files.")
      val metrics = try {
        optimizeByReorg(sparkSession)
      } catch {
        case NonFatal(e) =>
          throw DeltaErrors.icebergCompatDataFileRewriteFailedException(
            targetIcebergCompatVersion, e)
      }
      logInfo(log"Rewrite table ${MDC(DeltaLogKeys.TABLE_NAME, target.tableIdentifier)} " +
        log"to iceberg compat version = ${MDC(DeltaLogKeys.VERSION,
          targetIcebergCompatVersion)} successfully.")
      (Some(metrics), true)
    } else {
      (None, false)
    }
    val updatedSnapshot = target.deltaLog.update()
    val (numOfAddFiles, numOfAddFilesWithIcebergCompatTag) = getNumOfAddFiles(
      targetIcebergCompatVersion, target, updatedSnapshot)
    if (mayNeedRewrite && numOfAddFilesWithIcebergCompatTag != numOfAddFiles) {
      throw DeltaErrors.icebergCompatReorgAddFileTagsMissingException(
        updatedSnapshot.version,
        targetIcebergCompatVersion,
        numOfAddFiles,
        numOfAddFilesWithIcebergCompatTag
      )
    }

    // Step 3: Update the table properties to enable the universalFormat = Iceberg.
    if (!icebergEnabled(updatedSnapshot.metadata)) {
      val enableUniformConf = Map(
        DeltaConfigs.UNIVERSAL_FORMAT_ENABLED_FORMATS.key -> ICEBERG_FORMAT)
      AlterTableSetPropertiesDeltaCommand(target, enableUniformConf).run(sparkSession)
      logInfo(log"Enabling universal format with iceberg compat version = " +
        log"${MDC(DeltaLogKeys.VERSION, targetIcebergCompatVersion)} for table " +
        log"${MDC(DeltaLogKeys.TABLE_NAME, target.tableIdentifier)} succeeded.")
    }

    recordDeltaEvent(updatedSnapshot.deltaLog, "delta.upgradeUniform.success", data = Map(
      "currIcebergCompatVersion" -> currIcebergCompatVersionOpt.toString,
      "targetIcebergCompatVersion" -> targetIcebergCompatVersion.toString,
      "metrics" -> metricsOpt.toString,
      "didUpdateIcebergCompatVersion" -> didUpdateIcebergCompatVersion.toString,
      "needRewrite" -> mayNeedRewrite.toString,
      "didRewrite" -> didRewrite.toString,
      "numOfAddFilesBefore" -> numOfAddFilesBefore.toString,
      "numOfAddFilesWithIcebergCompatTagBefore" -> numOfAddFilesWithTagBefore.toString,
      "numOfAddFilesAfter" -> numOfAddFiles.toString,
      "numOfAddFilesWithIcebergCompatTagAfter" -> numOfAddFilesWithIcebergCompatTag.toString,
      "universalFormatIcebergEnabled" -> icebergEnabled(target.deltaLog.update().metadata).toString
    ))
    metricsOpt.getOrElse(Seq.empty[Row])
  }

  /**
   * Helper function to upgrade the table to uniform iceberg compat version.
   */
  protected def upgradeUniformIcebergCompatVersion(
      target: DeltaTableV2,
      sparkSession: SparkSession,
      targetIcebergCompatVersion: Int): Seq[Row] = {
    try {
      doRewrite(target, sparkSession, targetIcebergCompatVersion)
    } catch {
      case NonFatal(e) =>
        recordDeltaEvent(target.deltaLog, "delta.upgradeUniform.exception", data = Map(
          "targetIcebergCompatVersion" -> targetIcebergCompatVersion.toString,
          "exception" -> e.toString
        ))
        throw e
    }
  }
}
