package com.sample

import com.jcraft.jsch.JSch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.lib.Constants
import java.io.File
import java.io.IOException
import java.util.Hashtable
import kotlin.platform.platformStatic

object Main {

	platformStatic fun main(args: Array<String>){
		val svnPrefix = args[0]
		val targetFolder = args[1]
		val maintenanceBranch = args[2]
		val topicHash = args[3]
		println("svnPrefix = $svnPrefix")
		println("targetFolder = $targetFolder")
		println("maintenanceBranch = $maintenanceBranch")
		println("topicHash = $topicHash")

		JSch.setConfig("StrictHostKeyChecking", "no")

		val repository: FileRepository = FileRepository(File(targetFolder + Constants.DOT_GIT))
		val git = Git(repository)
		val maintenanceHash = git.getRepository().getRef("refs/heads/$maintenanceBranch").getObjectId().getName()
		println("mainHash: $maintenanceHash")
		try {
			println("--stash local change--")
			val stash = git.stashCreate().setRef(maintenanceHash).call()
			System.out.println("Created stash " + stash)

			println("--fetch & check targetRepo has correct origin--")
			val fResult = git.fetch().call()
			println(fResult.getURI().toString())

			println("--change branch(svn branch)--")
			git.checkout().setName("refs/remotes/$svnPrefix$maintenanceBranch").call()

			println("--get author--")
			val targetRev = Util.findRevCommit(git, topicHash)
			val authorName = targetRev.getAuthorIdent().getName()
			println(authorName)

			println("--git-svn rebase--")
			val s1 = Util.externalCommandExec("./shell/gitSvnRebase.sh", targetFolder, authorName, authorName)
			if(s1.second != 0){
				println("Error: in this git-svn repo, $maintenanceBranch branch status is something wrong")
				println(s1.first)
				throw IOException("shell Error: ")
			}
			println(s1.first)

			println("--git push force(when svn repo was updated)--")
			git.branchDelete().setBranchNames(maintenanceBranch).setForce(true).call()
			git.branchCreate().setName(maintenanceBranch).call()
			git.checkout().setName("refs/heads/$maintenanceBranch").call()
			try{
				git.push().setForce(true).call()
			}catch(e: TransportException){
				println(e.getMessage())
			}
			git.checkout().setName("refs/remotes/$svnPrefix$maintenanceBranch").call()

			println("--merge--")
			val topicBranch = Util.findBranchNameFromHeadHash(git, topicHash)
			println("topicBranch: $topicBranch")
			println("merge $maintenanceBranch <- $topicBranch")
			val mResult = git.merge().include(targetRev).call()
			if (!mResult.getMergeStatus().isSuccessful()) {
				println("Error: conflict happen")
				mResult.getConflicts().forEach { v -> println(v) }
				throw IOException("conflict error: " + mResult.getConflicts().keySet().join(","))
			}

			println("--git-svn dcommit--")
			val s2 = Util.externalCommandExec("./shell/gitSvnDcommit.sh", targetFolder, authorName, authorName)
			if(s2.second != 0){
				println("Error: svn reject your commit. check your commit [merge old commit] or [svn hook]")
				println(s2.first)
				throw IOException("shell Error: ")
			}
			println(s2.first)

			println("--git push force(update git-svn_dcommit data)--")
			git.branchDelete().setBranchNames(maintenanceBranch).setForce(true).call()
			git.branchCreate().setName(maintenanceBranch).call()
			git.checkout().setName("refs/heads/$maintenanceBranch").call()
			try{
				git.push().setForce(true).call()
			}catch(e: TransportException){
				println(e.getMessage())
			}

			println("Success: all done")
		}catch(e:Exception){
			e.printStackTrace()
			println("--reset hard--")
			git.reset().setMode(ResetCommand.ResetType.HARD).setRef(maintenanceHash).call()
			Util.externalCommandExec("./shell/rmGitSvnRebaseApply.sh", targetFolder)
			throw Exception("Fail: check logs")
		}
	}
}